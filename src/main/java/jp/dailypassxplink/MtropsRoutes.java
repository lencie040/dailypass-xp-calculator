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
    private Network cached;
    private long cachedAt;
    private final MtropsLoops loops = new MtropsLoops(this);

    MtropsRoutes(Supplier<DailyPassFares.Data> fareData) {
        this.fareData = fareData;
    }

    String read() {
        Network network = network();
        return GSON.toJson(new Result(network.state, network.fares != null && network.fares.distanceFare(),
                network.fares == null ? Map.of() : network.fares.names(),
                network.patterns.stream().map(Pattern::route).toList()));
    }

    String readLoops(int transferSeconds) {
        return GSON.toJson(loops.read(network(), transferSeconds));
    }

    private synchronized Network network() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;
        try {
            JsonObject status = fetch("status");
            if (!flag(status, "connected") || flag(status, "stale")) return Network.unavailable("stale");
            JsonArray routeList = array(fetch("routes"), "routes");
            DailyPassFares.Data fares = fareData.get();
            var details = new ArrayList<CompletableFuture<Pattern>>();
            for (JsonElement entry : routeList) {
                if (entry.isJsonObject()) {
                    JsonObject summary = entry.getAsJsonObject();
                    details.add(CompletableFuture.supplyAsync(() -> route(summary, fares), requests));
                }
            }

            List<Pattern> patterns = details.stream().map(CompletableFuture::join).toList();
            cached = new Network("ready", flag(status, "scheduleReceived"), (long) number(status, "topologyRev"), fares, patterns);
            cachedAt = System.currentTimeMillis();
            return cached;
        } catch (IOException | RuntimeException e) {
            return Network.unavailable("unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Network.unavailable("unavailable");
        }
    }

    private Pattern route(JsonObject summary, DailyPassFares.Data fares) {
        String id = label(summary, "id");
        String name = label(summary, "name");
        String number = label(summary, "number");
        String status = label(summary, "status");
        try {
            if (!validId(id)) throw new IOException("Invalid route ID");
            JsonObject detail = fetch("routes/" + id + "?points=0");
            JsonArray sectionList = array(detail, "segments");
            List<Station> stations = new ArrayList<>();
            for (JsonElement entry : array(detail, "stations")) {
                JsonObject stop = entry.getAsJsonObject();
                stations.add(new Station(label(stop, "name"), number(stop, "cumulativeM"), label(stop, "id")));
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
                sections.add(new Section(number(section, "distanceM"), expected > 0 ? expected : theory, theory,
                        number(runtime, "dwellSec"), flag(section, "measured")));
            }
            double meters = number(detail, "totalM");
            Timing expected = timing(sections, false);
            Timing theory = timing(sections, true);
            Station first = stations.isEmpty() ? null : stations.get(0);
            Station last = stations.isEmpty() ? null : stations.get(stations.size() - 1);
            Map<String, Double> companyMeters = Map.of();
            if (hasStations && meters > 0 && fares != null && fares.distanceFare()) {
                try {
                    companyMeters = fares.quote(name, stations).companyMeters();
                } catch (ReflectiveOperationException e) {
                    // Distance and timing are still available even if DailyPass cannot attribute this route.
                }
            }
            Route route = new Route(id, name, number, label(summary, "destination"), status,
                    first == null ? "" : first.name, last == null ? "" : last.name,
                    stations.size(), meters, flag(detail, "allMeasured"), expected, theory,
                    companyMeters, true);
            return new Pattern(route, hasStations ? List.copyOf(stations) : List.of(), List.copyOf(sections));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // Keep the route visible when its details cannot be loaded.
        }
        return new Pattern(new Route(id, name, number, label(summary, "destination"), status,
                "", "", 0, -1, false, Timing.UNKNOWN, Timing.UNKNOWN, Map.of(), false), List.of(), List.of());
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

    CompletableFuture<JsonObject> stationTimetable(String id) {
        return CompletableFuture.supplyAsync(() -> {
            if (!validId(id)) return null;
            try {
                return fetch("schedule/" + id + "?hours=2");
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }, requests);
    }

    JsonObject depotList() throws IOException, InterruptedException {
        return fetch("depots");
    }

    private static boolean validId(String id) {
        return id.matches("-?[0-9]{1,20}");
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

    static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Missing array");
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static String label(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    static boolean flag(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    static double number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        double number = value.getAsDouble();
        return Double.isFinite(number) && number >= 0 ? number : -1;
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }

    record Station(String name, double meters, String id) {}
    record Section(double meters, double expected, double theory, double dwell, boolean measured) {}
    record Timing(double seconds, int estimatedSections) {
        private static final Timing UNKNOWN = new Timing(-1, 0);
    }
    record Route(String id, String name, String number, String destination, String status,
                         String from, String to, int stations, double meters, boolean measured,
                         Timing expected, Timing theory, Map<String, Double> companyMeters, boolean available) {}
    record Pattern(Route route, List<Station> stops, List<Section> sections) {}
    record Network(String state, boolean scheduleReady, long revision, DailyPassFares.Data fares, List<Pattern> patterns) {
        static Network unavailable(String state) {
            return new Network(state, false, -1, null, List.of());
        }
    }
    private record Result(String state, boolean distanceFare, Map<String, String> companies, List<Route> routes) {}
}
