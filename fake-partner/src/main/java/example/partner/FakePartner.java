package example.partner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for an external partner. It runs as its own process and is used only while recording.
 * In replay it is switched off, and the service gets the partner's answers from the recording.
 *
 * Each risk answer carries a fresh random reference. That makes the point visible: a replayed
 * response shows the reference from the recording, which this process could never produce again.
 */
public final class FakePartner {

    private static final Map<String, String> BASE_RATES = Map.of(
            "home", "0.0031",
            "car", "0.0420",
            "travel", "0.0150");

    private static final Pattern CUSTOMER_ID = Pattern.compile("\"customerId\"\\s*:\\s*(\\d+)");

    private FakePartner() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PARTNER_PORT", "8082"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/rates/", FakePartner::rate);
        server.createContext("/fees/", FakePartner::fee);
        server.createContext("/risk-scores", FakePartner::riskScore);
        server.start();
        System.out.println("fake-partner listening on port " + port);
    }

    /** GET /rates/{product}?region=NORTH */
    private static void rate(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String product = path.substring(path.lastIndexOf('/') + 1);
        String rate = BASE_RATES.get(product);
        if (rate == null) {
            send(exchange, 404, "{\"error\":\"unknown product\"}");
            return;
        }
        send(exchange, 200, "{\"product\":\"" + product + "\",\"baseRate\":" + rate + "}");
    }

    /** GET /fees/{product} */
    private static void fee(HttpExchange exchange) throws IOException {
        send(exchange, 200, "{\"policyFee\":25.00}");
    }

    /** POST /risk-scores with a JSON body naming the customer. */
    private static void riskScore(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = CUSTOMER_ID.matcher(body);
        long customerId = matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
        String riskFactor = "1." + String.format("%02d", 5 + customerId % 20);
        String reference = "RS-" + UUID.randomUUID().toString().substring(0, 8);
        send(exchange, 200, "{\"riskFactor\":" + riskFactor + ",\"reference\":\"" + reference + "\"}");
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " -> " + status);
    }
}
