package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import example.regtests.Comparer.Difference;
import example.regtests.Model.HttpCall;
import example.regtests.Model.JdbcCall;
import example.regtests.Model.RecordedCase;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers one replayed request from its recording, and notes every way the new build behaves
 * differently from the recorded one.
 */
final class ReplaySession extends Session {

    private record CallKey(String type, String key) {
    }

    private final RecordedCase recorded;
    private final Scrubber scrubber;
    private final Comparer comparer;
    private final Map<CallKey, Queue<Object>> queues = new ConcurrentHashMap<>();
    private final List<Difference> differences = new CopyOnWriteArrayList<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final AtomicInteger callsCompared = new AtomicInteger();
    private volatile int actualStatus;
    private volatile String serviceError;

    ReplaySession(String runId, RecordedCase recorded, Scrubber scrubber, Comparer comparer) {
        super(runId);
        this.recorded = recorded;
        this.scrubber = scrubber;
        this.comparer = comparer;
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

    JdbcCall answerJdbc(String sql, JsonNode rawParams) {
        String text = SqlText.normalize(sql);
        JdbcCall call = (JdbcCall) take(jdbcKey(text));
        if (call == null) {
            throw unexpected("database call: " + text);
        }
        callsCompared.incrementAndGet();
        differences.addAll(comparer.compareParams(text, call.params(), scrubber.params(text, rawParams)));
        return call;
    }

    HttpCall answerHttp(String method, String url, String requestBody) {
        HttpCall call = (HttpCall) take(httpKey(method, url));
        if (call == null) {
            throw unexpected("outbound call: " + method + " " + url);
        }
        callsCompared.incrementAndGet();
        String where = "Outbound " + method + " " + URI.create(url).getPath();
        Difference query = comparer.compareText(where, "query", URI.create(call.url()).getQuery(), URI.create(url).getQuery());
        if (query != null) {
            differences.add(query);
        }
        differences.addAll(comparer.compareJson(where, call.requestBody(), scrubber.json(Json.parseBody(requestBody))));
        return call;
    }

    /** What the service threw. An outbound call no hook caught ends up here, naming its host. */
    void noteServiceError(Exception error) {
        serviceError = error.getMessage();
    }

    /** Compares status and body, and returns what this step found. */
    List<Difference> compareResponse(int status, String body) {
        actualStatus = status;
        List<Difference> found = new ArrayList<>();
        Difference statusDifference = comparer.compareText("Response", "status",
                String.valueOf(recorded.inbound().response().status()), String.valueOf(status));
        if (statusDifference != null) {
            found.add(statusDifference);
        }
        found.addAll(comparer.compareJson("Response", recorded.inbound().response().body(),
                scrubber.json(Json.parseBody(body))));
        differences.addAll(found);
        return found;
    }

    /** Call once the response is out and async work has finished, or the wait has run out. */
    ReplayReport report(boolean timedOut) {
        List<String> missing = new ArrayList<>();
        queues.forEach((key, queue) -> queue.forEach(left -> missing.add(key.key())));
        boolean passed = differences.isEmpty() && unexpected.isEmpty() && missing.isEmpty() && !timedOut;
        return new ReplayReport(recorded.inbound().caseName(), requestId(), passed, actualStatus, callsCompared.get(),
                List.copyOf(differences), List.copyOf(unexpected), missing, serviceError, timedOut);
    }

    // REG-22: a call the recording does not contain fails loudly. It is thrown into the service, kept for the report in case the service swallows it, and nothing goes out.
    private UnexpectedCallException unexpected(String call) {
        String message = "REPLAY: unexpected " + call + ". Case " + recorded.inbound().caseName()
                + " has no such call left. Nothing was sent anywhere.";
        unexpected.add(message);
        return new UnexpectedCallException(message);
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
