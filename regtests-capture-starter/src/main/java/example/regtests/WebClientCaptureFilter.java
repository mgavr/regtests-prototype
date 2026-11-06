package example.regtests;

import example.regtests.Model.HttpCall;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The outbound hook for WebClient. The session is read from thread context when the filter runs,
 * which is on the calling thread, and then carried in the closures below, because the response
 * arrives on a different thread.
 */
final class WebClientCaptureFilter implements ExchangeFilterFunction {

    private final Regtests regtests;
    private final ExchangeStrategies strategies;

    WebClientCaptureFilter(Regtests regtests, ExchangeStrategies strategies) {
        this.regtests = regtests;
        this.strategies = strategies;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String method = request.method().name();
        String url = request.url().toString();

        if (regtests.mode() == RegtestsMode.REPLAY) {
            ReplaySession session;
            try {
                session = ReplaySession.require(method + " " + url);
            } catch (UnexpectedCallException e) {
                return Mono.error(e);
            }
            return bodyOf(request).flatMap(buffered -> replay(session, request, buffered));
        }
        if (Session.current() instanceof RecordSession session) {
            return bodyOf(request).flatMap(buffered -> record(session, request, buffered, next));
        }
        return next.exchange(request);
    }

    private Mono<ClientResponse> replay(ReplaySession session, ClientRequest request, BufferedRequest buffered) {
        String method = request.method().name();
        try {
            // REG-21: answered from the recording. next.exchange() is never called, so no socket is opened.
            HttpCall call = session.answerHttp(method, request.url().toString(), buffered.bodyText());
            regtests.events().emit("REG-21", WebClientCaptureFilter.class,
                    "Outbound call answered from the recording: " + method + " " + request.url().getPath(),
                    "WebClient call, HTTP " + call.status() + ", no network used. Body: " + Json.bodyText(call.responseBody()));
            ClientResponse.Builder response = ClientResponse.create(HttpStatusCode.valueOf(call.status()), strategies)
                    .body(Json.bodyText(call.responseBody()));
            if (call.contentType() != null) {
                response.header(HttpHeaders.CONTENT_TYPE, call.contentType());
            }
            return Mono.just(response.build());
        } catch (UnexpectedCallException e) {
            return Mono.error(e);
        }
    }

    private Mono<ClientResponse> record(RecordSession session, ClientRequest request, BufferedRequest buffered,
                                        ExchangeFunction next) {
        String method = request.method().name();
        byte[] bytes = buffered.bytes();
        ClientRequest forwarded = ClientRequest.from(request)
                .headers(headers -> headers.putAll(buffered.getHeaders()))
                .body((output, context) -> output.writeWith(Mono.fromSupplier(() -> output.bufferFactory().wrap(bytes))))
                .build();
        return next.exchange(forwarded).flatMap(response -> response.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
            // REG-9: the WebClient call, recorded under the same request id even though this line runs on a reactor thread.
            HttpCall call = session.recordHttp(method, request.url().toString(), buffered.bodyText(),
                    response.statusCode().value(), String.valueOf(response.headers().contentType().orElse(null)), body);
            regtests.events().emit("REG-9", WebClientCaptureFilter.class,
                    "Outbound call captured: " + method + " " + request.url().getPath(),
                    "WebClient call under request id " + session.requestId() + ", HTTP " + call.status()
                            + ". Sent: " + Json.bodyText(call.requestBody()) + " Answer: " + Json.bodyText(call.responseBody()));
            return ClientResponse.create(response.statusCode(), strategies)
                    .headers(headers -> headers.addAll(response.headers().asHttpHeaders()))
                    .body(body)
                    .build();
        }));
    }

    /** Runs the application's own body writer into memory, so both modes see the exact bytes. */
    private Mono<BufferedRequest> bodyOf(ClientRequest request) {
        BufferedRequest buffered = new BufferedRequest(request.method(), request.url());
        return request.writeTo(buffered, strategies).thenReturn(buffered);
    }

    /** A request that goes nowhere: what is "sent" to it stays in memory. */
    private static final class BufferedRequest implements ClientHttpRequest {

        private final HttpMethod method;
        private final URI uri;
        private final HttpHeaders headers = new HttpHeaders();
        private final MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
        private final Map<String, Object> attributes = new HashMap<>();
        private final DataBufferFactory bufferFactory = DefaultDataBufferFactory.sharedInstance;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        BufferedRequest(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        byte[] bytes() {
            return body.toByteArray();
        }

        String bodyText() {
            return body.toString(StandardCharsets.UTF_8);
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public MultiValueMap<String, HttpCookie> getCookies() {
            return cookies;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public <T> T getNativeRequest() {
            throw new UnsupportedOperationException("This request is never sent");
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return bufferFactory;
        }

        @Override
        public void beforeCommit(Supplier<? extends Mono<Void>> action) {
            // Never committed.
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> content) {
            return Flux.from(content).doOnNext(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                body.writeBytes(bytes);
            }).then();
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> content) {
            return writeWith(Flux.from(content).flatMap(inner -> inner));
        }

        @Override
        public Mono<Void> setComplete() {
            return Mono.empty();
        }
    }
}
