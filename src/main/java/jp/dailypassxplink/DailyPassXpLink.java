package jp.dailypassxplink;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class DailyPassXpLink implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("dailypass-xp-link");
    private static final Identifier REQUEST = new Identifier("dailypass", "level_request");
    private final LocalSite site = new LocalSite(this);
    private volatile Progress progress = new Progress("offline", null);
    private volatile DailyPassFares.Data fareData;
    private ProgressReader reader;
    private List<?> observedEntries;
    private DailyPassFares fares;
    private long nextFareRead;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                reader = new ProgressReader();
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.warn("DailyPass level viewer is unavailable", e);
            }
            try {
                fares = new DailyPassFares();
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.warn("DailyPass company distance calculator is unavailable", e);
            }
            site.start();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> site.close());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private void tick(MinecraftClient client) {
        long now = Util.getMeasuringTimeMs();
        if (fares != null && now >= nextFareRead) {
            nextFareRead = now + 5_000;
            try {
                fareData = fares.capture();
            } catch (ReflectiveOperationException e) {
                fares = null;
                LOG.warn("Could not read DailyPass company data", e);
            }
        }

        if (client.player == null || client.getNetworkHandler() == null) return;
        if (reader != null && reader.isViewer(client.currentScreen)) {
            capture(client);
        }
        if (progress.snapshot == null) {
            String state = reader != null && ClientPlayNetworking.canSend(REQUEST) ? "waitingForViewer" : "unsupported";
            if (!state.equals(progress.state)) progress = new Progress(state, null);
        }
    }

    DailyPassFares.Data fareData() {
        return fareData;
    }

    private void capture(MinecraftClient client) {
        try {
            // The DailyPass screen already owns the decoded reply from its native item.
            List<?> entries = reader.entries(client.currentScreen);
            if (observedEntries == entries) return;
            observedEntries = entries;
            Snapshot snapshot = reader.ownProgress(entries, client.player.getGameProfile().getName());
            if (snapshot != null) progress = new Progress("ready", snapshot);
        } catch (ReflectiveOperationException | RuntimeException e) {
            reader = null;
            LOG.warn("Could not read the DailyPass level viewer", e);
        }
    }

    private void reset() {
        observedEntries = null;
        progress = new Progress("offline", null);
    }

    String readProgress() {
        Progress current = progress;
        Snapshot value = current.snapshot;
        if (value == null) return "{\"state\":\"" + current.state + "\"}";
        return "{\"state\":\"ready\",\"stars\":" + value.stars + ",\"level\":" + value.level
                + ",\"xp\":" + value.xp + ",\"need\":" + value.need + ",\"updatedAt\":" + value.updatedAt + "}";
    }

    private record Progress(String state, Snapshot snapshot) {}
    private record Snapshot(int stars, int level, long xp, long need, long updatedAt) {}

    private static final class ProgressReader {
        private final Class<?> viewer;
        private final Field entries;
        private final Method name, overall, stars, level, xp, need;

        ProgressReader() throws ReflectiveOperationException {
            viewer = Class.forName("jp.dailypass.progress.client.LevelScreen");
            entries = viewer.getDeclaredField("entries");
            entries.setAccessible(true);
            Class<?> entry = Class.forName("jp.dailypass.progress.LevelNetwork$Entry");
            name = entry.getMethod("name");
            overall = entry.getMethod("overall");
            Class<?> row = overall.getReturnType();
            stars = row.getMethod("stars");
            level = row.getMethod("level");
            xp = row.getMethod("xp");
            need = row.getMethod("need");
        }

        boolean isViewer(Screen screen) {
            return viewer.isInstance(screen);
        }

        List<?> entries(Screen screen) throws IllegalAccessException {
            return (List<?>) entries.get(screen);
        }

        Snapshot ownProgress(List<?> entries, String playerName) throws ReflectiveOperationException {
            for (Object entry : entries) {
                if (!playerName.equals(name.invoke(entry))) continue;
                Object row = overall.invoke(entry);
                return new Snapshot((int) stars.invoke(row), (int) level.invoke(row),
                        (long) xp.invoke(row), (long) need.invoke(row), System.currentTimeMillis());
            }
            return null;
        }
    }
}
