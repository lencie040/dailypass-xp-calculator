package jp.dailypassxplink;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class DailyPassXpLink implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("dailypass-xp-link");
    private static final Identifier REQUEST = new Identifier("dailypass", "level_request");
    private static final Identifier DATA = new Identifier("dailypass", "level_data");
    private static final long REQUEST_INTERVAL_MS = 8_000;
    private static final long SNAPSHOT_LIFETIME_MS = 24_000;
    private static final long VIEWER_WINDOW_MS = 20_000;

    private final AtomicReference<ClientPlayNetworkHandler> pending = new AtomicReference<>();
    private final LocalSite site = new LocalSite(this);
    private volatile Snapshot snapshot;
    private volatile long lastVisit;
    private volatile String state = "offline";
    private ProgressReader reader;
    private long nextRequest;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (attach()) site.start();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> site.close());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private boolean attach() {
        try {
            reader = new ProgressReader();
            var hook = Class.forName("jp.dailypass.progress.LevelViewerItem").getField("requestHook");
            Runnable manual = (Runnable) hook.get(null);
            var receiver = ClientPlayNetworking.unregisterGlobalReceiver(DATA);
            if (manual == null || receiver == null) {
                if (receiver != null) ClientPlayNetworking.registerGlobalReceiver(DATA, receiver);
                LOG.warn("DailyPass level viewer is unavailable");
                reader = null;
                return false;
            }

            ClientPlayNetworking.registerGlobalReceiver(DATA, (client, handler, buffer, sender) -> {
                if (!pending.compareAndSet(handler, null)) {
                    receiver.receive(client, handler, buffer, sender);
                    return;
                }
                try {
                    List<?> entries = reader.read(buffer);
                    client.execute(() -> apply(client, handler, entries));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Could not read DailyPass level data", e);
                }
            });
            hook.set(null, (Runnable) () -> {
                pending.set(null);
                nextRequest = Util.getMeasuringTimeMs() + REQUEST_INTERVAL_MS;
                manual.run();
            });
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            reader = null;
            LOG.warn("Could not start DailyPass level link", e);
            return false;
        }
    }

    private void tick(MinecraftClient client) {
        if (reader == null || client.player == null || client.getNetworkHandler() == null) return;
        if (!ClientPlayNetworking.canSend(REQUEST)) {
            state = "unsupported";
            return;
        }
        state = "waiting";
        long now = Util.getMeasuringTimeMs();
        long visit = lastVisit;
        if (visit == 0 || now - visit > VIEWER_WINDOW_MS || now < nextRequest) return;
        pending.set(client.getNetworkHandler());
        nextRequest = now + REQUEST_INTERVAL_MS;
        ClientPlayNetworking.send(REQUEST, PacketByteBufs.empty());
    }

    private void apply(MinecraftClient client, ClientPlayNetworkHandler handler, List<?> entries) {
        if (client.getNetworkHandler() != handler || client.player == null) return;
        try {
            snapshot = reader.ownProgress(entries, client.player.getGameProfile().getName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Could not find own DailyPass progress", e);
        }
    }

    private void reset() {
        pending.set(null);
        snapshot = null;
        state = "offline";
        nextRequest = 0;
    }

    String visit() {
        long now = Util.getMeasuringTimeMs();
        lastVisit = now;
        Snapshot value = snapshot;
        if (value == null || now - value.receivedAt > SNAPSHOT_LIFETIME_MS) {
            return "{\"state\":\"" + state + "\"}";
        }
        return "{\"state\":\"ready\",\"stars\":" + value.stars + ",\"level\":" + value.level
                + ",\"xp\":" + value.xp + ",\"need\":" + value.need + "}";
    }

    private record Snapshot(int stars, int level, long xp, long need, long receivedAt) {}

    private static final class ProgressReader {
        private final Method read, name, overall, stars, level, xp, need;

        ProgressReader() throws ReflectiveOperationException {
            read = Class.forName("jp.dailypass.progress.LevelNetwork").getMethod("read", PacketByteBuf.class);
            Class<?> entry = Class.forName("jp.dailypass.progress.LevelNetwork$Entry");
            name = entry.getMethod("name");
            overall = entry.getMethod("overall");
            Class<?> row = overall.getReturnType();
            stars = row.getMethod("stars");
            level = row.getMethod("level");
            xp = row.getMethod("xp");
            need = row.getMethod("need");
        }

        List<?> read(PacketByteBuf packet) throws ReflectiveOperationException {
            return (List<?>) read.invoke(null, packet);
        }

        Snapshot ownProgress(List<?> entries, String playerName) throws ReflectiveOperationException {
            for (Object entry : entries) {
                if (!playerName.equals(name.invoke(entry))) continue;
                Object row = overall.invoke(entry);
                return new Snapshot((int) stars.invoke(row), (int) level.invoke(row),
                        (long) xp.invoke(row), (long) need.invoke(row), Util.getMeasuringTimeMs());
            }
            return null;
        }
    }
}
