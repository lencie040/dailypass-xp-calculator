package jp.dailypassxplink;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

final class MtropsRoutes implements AutoCloseable {
    private static final String API_ENDPOINT = "http://127.0.0.1:8765/api/";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final long CACHE_MS = 4_000;
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            })
            .build();
    private final ExecutorService requests = Executors.newFixedThreadPool(5, task -> {
        Thread thread = new Thread(task, "DailyPass XP Link MTROPS");
        thread.setDaemon(true);
        return thread;
    });
    private final Supplier<DailyPassFares.Data> fareData;
    private String cached;
    private long cachedAt;

    MtropsRoutes(Supplier<DailyPassFares.Data> fareData) {
        this.fareData = fareData;
    }

    synchronized String read() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;
        try {
            JsonObject status = fetch("status");
            if (!flag(status, "connected") || flag(status, "stale")) return "{\"state\":\"stale\"}";
            JsonArray routeList = array(fetch("routes"), "routes");
            DailyPassFares.Data fares = fareData.get();
            var details = new ArrayList<CompletableFuture<Route>>();
            for (JsonElement entry : routeList) {
                if (entry.isJsonObject()) {
                    JsonObject summary = entry.getAsJsonObject();
                    details.add(CompletableFuture.supplyAsync(() -> route(summary, fares), requests));
                }
            }

            List<Route> routes = details.stream().map(CompletableFuture::join).toList();
            cached = GSON.toJson(new Result("ready", fares != null && fares.distanceFare(),
                    fares == null ? Map.of() : fares.names(), routes));
            cachedAt = System.currentTimeMillis();
            return cached;
        } catch (IOException | RuntimeException e) {
            return "{\"state\":\"unavailable\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"state\":\"unavailable\"}";
        }
    }

    private Route route(JsonObject summary, DailyPassFares.Data fares) {
        String id = label(summary, "id");
        String name = label(summary, "name");
        String number = label(summary, "number");
        String status = label(summary, "status");
        try {
            if (!id.matches("-?[0-9]{1,20}")) throw new IOException("Invalid route ID");
            JsonObject detail = fetch("routes/" + id + "?points=0");
            JsonArray sectionList = array(detail, "segments");
            List<Station> stations = new ArrayList<>();
            for (JsonElement entry : array(detail, "stations")) {
                JsonObject stop = entry.getAsJsonObject();
                stations.add(new Station(label(stop, "name"), number(stop, "cumulativeM")));
            }

            boolean hasStations = stations.size() >= 2 && stations.size() == sectionList.size() + 1
                    && stations.stream().allMatch(s -> s.meters >= 0);
            List<Section> sections = new ArrayList<>();
            for (JsonElement entry : sectionList) {
                JsonObject section = entry.getAsJsonObject();
                JsonObject runtime = object(section, "runtime");
                double theory = number(runtime, "mtrSec");
                if (theory <= 0 && number(runtime, "runSec") > 0) {
                    theory = number(runtime, "runSec") + Math.max(0, number(runtime, "dwellSec"));
                }
                double expected = number(runtime, "travelSec");
                sections.add(new Section(number(section, "distanceM"), expected > 0 ? expected : theory, theory));
            }
            double meters = number(detail, "totalM");
            Timing expected = timing(sections, false);
            Timing theory = timing(sections, true);
            Station first = stations.isEmpty() ? null : stations.get(0);
            Station last = stations.isEmpty() ? null : stations.get(stations.size() - 1);
            Map<String, Double> companyMeters = Map.of();
            if (hasStations && meters > 0 && fares != null && fares.distanceFare()) {
                try {
                    companyMeters = fares.companyDistances(name, stations);
                } catch (ReflectiveOperationException e) {
                    // Distance and timing are still available even if DailyPass cannot attribute this route.
                }
            }
            return new Route(id, name, number, label(summary, "destination"), status,
                    first == null ? "" : first.name, last == null ? "" : last.name,
                    stations.size(), meters, flag(detail, "allMeasured"), expected, theory,
                    companyMeters, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // Keep the route visible when its details cannot be loaded.
        }
        return new Route(id, name, number, label(summary, "destination"), status,
                "", "", 0, -1, false, Timing.UNKNOWN, Timing.UNKNOWN, Map.of(), false);
    }

    private static Timing timing(List<Section> sections, boolean theory) {
        double knownMeters = 0;
        double knownSeconds = 0;
        double missingMeters = 0;
        int missing = 0;
        for (Section section : sections) {
            double seconds = theory ? section.theory : section.expected;
            if (section.meters < 0) return Timing.UNKNOWN;
            if (seconds > 0) {
                knownMeters += section.meters;
                knownSeconds += seconds;
            } else {
                missingMeters += section.meters;
                missing++;
            }
        }
        if (knownSeconds <= 0 || missing > 0 && !(knownMeters > 0 && missingMeters > 0)) return Timing.UNKNOWN;
        double estimate = missing == 0 ? 0 : missingMeters * knownSeconds / knownMeters;
        return new Timing(knownSeconds + estimate, missing);
    }

    private JsonObject fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ENDPOINT + path))
                .timeout(Duration.ofSeconds(3)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > MAX_BYTES) throw new IOException("MTROPS response unavailable");
        JsonObject value = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!flag(value, "ok")) throw new IOException("MTROPS did not return data");
        return value;
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Missing array");
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String label(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static boolean flag(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static double number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        double number = value.getAsDouble();
        return Double.isFinite(number) && number >= 0 ? number : -1;
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }

    record Station(String name, double meters) {}
    private record Section(double meters, double expected, double theory) {}
    private record Timing(double seconds, int estimatedSections) {
        private static final Timing UNKNOWN = new Timing(-1, 0);
    }
    private record Route(String id, String name, String number, String destination, String status,
                         String from, String to, int stations, double meters, boolean measured,
                         Timing expected, Timing theory, Map<String, Double> companyMeters, boolean available) {}
    private record Result(String state, boolean distanceFare, Map<String, String> companies, List<Route> routes) {}
}
