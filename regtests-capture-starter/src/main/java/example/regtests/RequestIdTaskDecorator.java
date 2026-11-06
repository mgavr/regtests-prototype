package example.regtests;

import org.springframework.core.task.TaskDecorator;

// REG-10: a new thread has no thread context, so without this @Async work would belong to no request. The session is handed over at submit time, and the case stays open until the task ends.
final class RequestIdTaskDecorator implements TaskDecorator {

    private final Regtests regtests;

    RequestIdTaskDecorator(Regtests regtests) {
        this.regtests = regtests;
    }

    @Override
    public Runnable decorate(Runnable task) {
        Session session = Session.current();
        if (session == null) {
            return task;
        }
        session.asyncStarted();
        return () -> {
            Session.bind(session);
            regtests.events().emit("REG-10", RequestIdTaskDecorator.class,
                    "Request id carried into @Async: " + session.requestId(),
                    "Now running on " + Thread.currentThread().getName() + ", a different thread from the request.");
            try {
                task.run();
            } finally {
                Session.unbind();
                session.asyncFinished();
            }
        };
    }
}
