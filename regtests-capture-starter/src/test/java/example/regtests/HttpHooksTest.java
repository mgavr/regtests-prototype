package example.regtests;

import com.sun.net.httpserver.HttpServer;
import example.regtests.Model.InboundRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every supported client, recorded against a live local server, then replayed with that server gone. */
class HttpHooksTest {

    @FunctionalInterface
    interface Fetch {
        String get(Regtests regtests, String url) throws Exception;
    }

    record Client(String name, Fetch fetch) {
        @Override
        public String toString() {
            return name;
        }
    }

    @TempDir
    Path recordings;

    private final AtomicInteger hits = new AtomicInteger();
    private HttpServer server;
    private String url;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/thing", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"answer\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/thing?a=1";
    }

    @AfterEach
    void cleanUp() {
        server.stop(0);
        Session.unbind();
        OutboundHttp.activate(null);
    }

    static Stream<Client> clients() {
        return Stream.of(
                new Client("RestTemplate", (regtests, url) -> {
                    RestTemplate restTemplate = new RestTemplate();
                    restTemplate.getInterceptors().add(new HttpCaptureInterceptor());
                    return restTemplate.getForObject(url, String.class);
                }),
                new Client("RestClient", (regtests, url) -> RestClient.builder().requestInterceptor(new HttpCaptureInterceptor())
                        .build().get().uri(url).retrieve().body(String.class)),
                new Client("WebClient", (regtests, url) -> WebClient.builder()
                        .filter(new WebClientCaptureFilter(regtests, ExchangeStrategies.withDefaults()))
                        .build().get().uri(url).retrieve().bodyToMono(String.class).block()),
                new Client("OkHttp", (regtests, url) -> viaOkHttp(url)),
                new Client("Apache HttpClient", (regtests, url) -> viaApache(url)));
    }

    private static String viaOkHttp(String url) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(RegtestsOkHttp.interceptor()).build();
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            return response.body().string();
        }
    }

    private static String viaApache(String url) throws IOException {
        try (CloseableHttpClient client = HttpClients.custom()
                .addExecInterceptorFirst("regtests", RegtestsApacheHttp.interceptor()).build()) {
            return client.execute(new HttpGet(url), response -> EntityUtils.toString(response.getEntity()));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void recordsThenReplaysWithNoNetwork(Client client) throws Exception {
        Regtests recording = Fixtures.regtests(RegtestsMode.RECORD, recordings);
        RecordSession session = new RecordSession("id", "a-case", recording.scrubber());
        Session.bind(session);

        String recorded = client.fetch().get(recording, url);

        session.captureInbound(new InboundRequest("GET", "/x", null, null, Json.parseBody("")), 200, null, "");
        session.save(recording.files());
        Session.unbind();
        server.stop(0);
        assertThat(hits).hasValue(1);

        Regtests replaying = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        ReplaySession replay = new ReplaySession("run", replaying.files().load("a-case"), replaying.scrubber());
        Session.bind(replay);

        String replayed = client.fetch().get(replaying, url);

        assertThat(replayed).isEqualTo(recorded).isEqualTo("{\"answer\":1}");
        assertThat(hits).hasValue(1);
        assertThat(replay.httpCalls()).singleElement().satisfies(written -> assertThat(written.status()).isEqualTo(200));
    }

    @Test
    void theAdaptersPassThroughWhenTheModeIsOff() throws IOException {
        OutboundHttp.activate(null);

        assertThat(viaOkHttp(url)).isEqualTo("{\"answer\":1}");
        assertThat(viaApache(url)).isEqualTo("{\"answer\":1}");
        assertThat(hits).hasValue(2);
    }
}
