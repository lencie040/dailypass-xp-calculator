package jp.dailypassxplink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

final class LocalSite implements AutoCloseable {
    static final int PORT = 38767;
    private static final String HOST = "127.0.0.1:" + PORT;
    private static final String PAGES_ORIGIN = "https://lencie040.github.io";
    private static final Logger LOG = LoggerFactory.getLogger("dailypass-xp-link");

    private final DailyPassXpLink link;
    private HttpServer server;

    LocalSite(DailyPassXpLink link) {
        this.link = link;
    }

    void start() {
        try (var resource = LocalSite.class.getResourceAsStream("/calculator.html")) {
            if (resource == null) throw new IOException("Calculator page is missing from the JAR");
            byte[] page = resource.readAllBytes();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    handle(exchange, page);
                }
            });
            server.start();
            LOG.info("DailyPass XP Link is available at http://{}/", HOST);
        } catch (IOException e) {
            close();
            LOG.warn("Could not open DailyPass XP Link on port {}", PORT, e);
        }
    }

    private void handle(HttpExchange exchange, byte[] page) throws IOException {
        if (!HOST.equals(exchange.getRequestHeaders().getFirst("Host"))) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/progress")) {
            handleProgress(exchange);
        } else if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
            send(exchange, "text/html", page);
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private void handleProgress(HttpExchange exchange) throws IOException {
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
            send(exchange, "application/json", link.visit().getBytes(StandardCharsets.UTF_8));
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
    }
}
