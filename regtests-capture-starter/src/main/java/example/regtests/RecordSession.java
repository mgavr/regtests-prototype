package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import example.regtests.Model.HttpCall;
import example.regtests.Model.Inbound;
import example.regtests.Model.InboundRequest;
import example.regtests.Model.InboundResponse;
import example.regtests.Model.JdbcCall;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Collects everything that crosses the service's edge during one request, already masked. */
final class RecordSession extends Session {

    private final String caseName;
    private final Scrubber scrubber;
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<HttpCall> httpCalls = new CopyOnWriteArrayList<>();
    private final List<JdbcCall> jdbcCalls = new CopyOnWriteArrayList<>();
    private volatile InboundRequest request;
    private volatile InboundResponse response;

    RecordSession(String requestId, String caseName, Scrubber scrubber) {
        super(requestId);
        this.caseName = caseName;
        this.scrubber = scrubber;
    }

    String caseName() {
        return caseName;
    }

    void captureInbound(InboundRequest captured, int status, String contentType, String body) {
        request = new InboundRequest(captured.method(), captured.path(), captured.query(), captured.contentType(),
                scrubber.json(captured.body()));
        response = new InboundResponse(status, contentType, scrubber.json(Json.parseBody(body)));
    }

    HttpCall recordHttp(String method, String url, String requestBody, int status, String contentType,
                        String responseBody) {
        HttpCall call = new HttpCall(sequence.incrementAndGet(), Thread.currentThread().getName(), method, url,
                scrubber.json(Json.parseBody(requestBody)), status, contentType,
                scrubber.json(Json.parseBody(responseBody)));
        httpCalls.add(call);
        return call;
    }

    JdbcCall recordJdbc(String sql, JsonNode params, Outcome outcome) {
        String text = SqlText.normalize(sql);
        JdbcCall call = new JdbcCall(sequence.incrementAndGet(), Thread.currentThread().getName(), text,
                scrubber.params(text, params), outcome.outValues(text, scrubber), outcome.resultValues(scrubber));
        jdbcCalls.add(call);
        return call;
    }

    int maskedSoFar() {
        return Scrubber.countMasked(Json.MAPPER.valueToTree(List.of(request, response, httpCalls, jdbcCalls)));
    }

    Path save(CaseFiles files) {
        Inbound inbound = new Inbound(caseName, requestId(), Instant.now().toString(), request, response);
        return files.save(caseName, Json.MAPPER.valueToTree(inbound), Json.MAPPER.valueToTree(httpCalls),
                Json.MAPPER.valueToTree(jdbcCalls));
    }
}
