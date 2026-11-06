package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import example.regtests.Model.HttpCall;
import example.regtests.Model.JdbcCall;
import example.regtests.Model.RecordedCase;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Answers one replayed request from its recording, and writes down everything the service attempts,
 * in the recording's own format. It judges nothing: comparing is the job of the test runner.
 */
final class ReplaySession extends RecordSession {

    private static final int NO_ANSWER = 0;

    private record CallKey(String type, String key) {
    }

    private final Map<CallKey, Queue<Object>> queues = new ConcurrentHashMap<>();

    ReplaySession(String runId, RecordedCase recorded, Scrubber scrubber) {
        super(runId, recorded.inbound().caseName(), scrubber);
        // REG-18: one queue per (type, key), not one global order. A call made on an @Async thread can land before or after anything else and still finds its answer.
        recorded.jdbc().forEach(call -> enqueue(jdbcKey(call.sql()), call));
        recorded.http().forEach(call -> enqueue(httpKey(call.method(), call.url()), call));
    }

    /** The session a hook must use in replay. With no request there is nothing to answer from. */
    static ReplaySession require(String attempted) {
        if (Session.current() instanceof ReplaySession session) {
            return session;
        }
        throw new UnexpectedCallException("REPLAY: " + attempted
                + " was attempted outside a replayed request. Nothing was sent anywhere.");
    }

    JdbcCall answerJdbc(String sql, JsonNode params) {
        String text = SqlText.normalize(sql);
        JdbcCall call = (JdbcCall) take(jdbcKey(text));
        recordJdbc(text, params, call == null ? Outcome.none() : Outcome.from(call));
        if (call == null) {
            throw unexpected("database call: " + text);
        }
        return call;
    }

    HttpCall answerHttp(String method, String url, String requestBody) {
        HttpCall call = (HttpCall) take(httpKey(method, url));
        if (call == null) {
            recordHttp(method, url, requestBody, NO_ANSWER, null, "");
            throw unexpected("outbound call: " + method + " " + url);
        }
        recordHttp(method, url, requestBody, call.status(), call.contentType(), Json.bodyText(call.responseBody()));
        return call;
    }

    /** A run gets its own folder, so it never overwrites another run or the recording. */
    @Override
    Path save(CaseFiles files) {
        return saveAs(requestId(), files);
    }

    // REG-22: a call the recording does not contain fails loudly. The service has nothing to answer it with, so this is thrown into the service. The attempt is already written down, and nothing goes out.
    private UnexpectedCallException unexpected(String call) {
        return new UnexpectedCallException("REPLAY: unexpected " + call + ". Case " + caseName()
                + " has no such call left. Nothing was sent anywhere.");
    }

    private void enqueue(CallKey key, Object call) {
        queues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(call);
    }

    private Object take(CallKey key) {
        Queue<Object> queue = queues.get(key);
        return queue == null ? null : queue.poll();
    }

    private static CallKey jdbcKey(String sql) {
        return new CallKey("jdbc", sql);
    }

    private static CallKey httpKey(String method, String url) {
        return new CallKey("http", method + " " + URI.create(url).getPath());
    }
}
