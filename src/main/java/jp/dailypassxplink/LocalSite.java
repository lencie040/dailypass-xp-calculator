package jp.dailypassxplink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class LocalSite implements AutoCloseable {
    static final int PORT = 38767;
    private static final String HOST = "127.0.0.1:" + PORT;
    private static final String PAGES_ORIGIN = "https://lencie040.github.io";
    private static final Logger LOG = LoggerFactory.getLogger("dailypass-xp-link");

    private final DailyPassXpLink link;
    private final MtropsRoutes mtrops;
    private HttpServer server;
    private ExecutorService requests;

    LocalSite(DailyPassXpLink link) {
        this.link = link;
        this.mtrops = new MtropsRoutes(link::fareData);
    }

    void start() {
        try {
            Map<String, Asset> pages = Map.of(
                    "/", asset("calculator.html", "text/html"),
                    "/routes.html", asset("routes.html", "text/html"),
                    "/routes.js", asset("routes.js", "text/javascript"),
                    "/loops.html", asset("loops.html", "text/html"),
                    "/loops.js", asset("loops.js", "text/javascript"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            requests = Executors.newFixedThreadPool(3, task -> {
                Thread thread = new Thread(task, "DailyPass XP Link HTTP");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(requests);
            server.createContext("/", exchange -> {
                try (exchange) {
                    handle(exchange, pages);
                }
            });
            server.start();
            LOG.info("DailyPass XP Link is available at http://{}/", HOST);
        } catch (IOException e) {
            close();
            LOG.warn("Could not open DailyPass XP Link on port {}", PORT, e);
        }
    }

    private static Asset asset(String name, String type) throws IOException {
        try (var resource = LocalSite.class.getResourceAsStream("/" + name)) {
            if (resource == null) throw new IOException("Missing calculator asset " + name);
            return new Asset(type, resource.readAllBytes());
        }
    }

    private void handle(HttpExchange exchange, Map<String, Asset> pages) throws IOException {
        if (!HOST.equals(exchange.getRequestHeaders().getFirst("Host"))) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/progress")) {
            handleJson(exchange, link::visit);
        } else if (path.equals("/api/mtrops/routes")) {
            handleJson(exchange, mtrops::read);
        } else if (path.equals("/api/mtrops/loops")) {
            handleJson(exchange, () -> mtrops.readLoops(transferTime(exchange.getRequestURI().getRawQuery())));
        } else if (pages.containsKey(path) && exchange.getRequestMethod().equals("GET")) {
            Asset page = pages.get(path);
            send(exchange, page.type, page.body);
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private static int transferTime(String query) {
        if (query != null && query.matches("transfer=[0-9]{1,3}")) {
            return Math.min(600, Integer.parseInt(query.substring("transfer=".length())));
        }
        return 15;
    }

    private void handleJson(HttpExchange exchange, Supplier<String> body) throws IOException {
        var request = exchange.getRequestHeaders();
        var response = exchange.getResponseHeaders();
        String origin = request.getFirst("Origin");
        // A browser must use the Pages site or the calculator served by this loopback process.
        if (PAGES_ORIGIN.equals(origin)) {
            response.set("Access-Control-Allow-Origin", PAGES_ORIGIN);
            response.set("Vary", "Origin");
        } else if (origin != null && !("http://" + HOST).equals(origin)
                || origin == null && "cross-site".equals(request.getFirst("Sec-Fetch-Site"))) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        if (exchange.getRequestMethod().equals("OPTIONS")) {
            response.set("Access-Control-Allow-Methods", "GET");
            exchange.sendResponseHeaders(204, -1);
        } else if (exchange.getRequestMethod().equals("GET")) {
            send(exchange, "application/json", body.get().getBytes(StandardCharsets.UTF_8));
        } else {
            exchange.sendResponseHeaders(405, -1);
        }
    }

    private static void send(HttpExchange exchange, String type, byte[] body) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (requests != null) {
            requests.shutdownNow();
            requests = null;
        }
        mtrops.close();
    }

    private record Asset(String type, byte[] body) {}
}
