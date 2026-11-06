package example.regtests;

import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One inbound request's worth of work, in either mode. The current session lives in thread
 * context. It also knows how much @Async work is still open, because such work can outlive the
 * response. A case is finished only when both are done.
 */
abstract class Session {

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private final String requestId;
    private final AtomicInteger openTasks = new AtomicInteger();
    private final CompletableFuture<Void> quiet = new CompletableFuture<>();
    private volatile boolean responseDone;

    protected Session(String requestId) {
        this.requestId = requestId;
    }

    static Session current() {
        return CURRENT.get();
    }

    static void bind(Session session) {
        CURRENT.set(session);
        MDC.put("requestId", session.requestId());
    }

    static void unbind() {
        CURRENT.remove();
        MDC.remove("requestId");
    }

    final String requestId() {
        return requestId;
    }

    final void asyncStarted() {
        openTasks.incrementAndGet();
    }

    final void asyncFinished() {
        openTasks.decrementAndGet();
        completeIfQuiet();
    }

    final void responseFinished() {
        responseDone = true;
        completeIfQuiet();
    }

    final CompletableFuture<Void> whenQuiet() {
        return quiet;
    }

    private void completeIfQuiet() {
        if (responseDone && openTasks.get() == 0) {
            quiet.complete(null);
        }
    }
}
