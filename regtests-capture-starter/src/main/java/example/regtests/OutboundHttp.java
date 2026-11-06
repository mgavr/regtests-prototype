package example.regtests;

import example.regtests.Model.HttpCall;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * The outbound path every blocking client goes through: RestTemplate, RestClient, and the OkHttp
 * and Apache adapters. Each of those only translates to and from this class.
 */
final class OutboundHttp {

    /** All this library keeps of a response. Other response headers are not recorded. */
    record Answer(int status, String contentType, byte[] body) {
    }

    @FunctionalInterface
    interface RealCall {
        Answer run() throws IOException;
    }

    private static volatile Regtests active;

    private OutboundHttp() {
    }

    /** Set when the mode is record or replay. Adapters built by hand have no other way to find it. */
    static void activate(Regtests regtests) {
        active = regtests;
    }

    static boolean isOn() {
        return active != null;
    }

    static Answer call(String client, String method, URI uri, String requestBody, RealCall real) throws IOException {
        Regtests regtests = active;
        String url = uri.toString();
        if (regtests.mode() == RegtestsMode.REPLAY) {
            // REG-20: answered from the recording. The real call is never run, so no socket is opened.
            HttpCall call = ReplaySession.require(method + " " + url).answerHttp(method, url, requestBody);
            String answer = Json.bodyText(call.responseBody());
            regtests.events().emit("REG-20", OutboundHttp.class, "Outbound call answered from the recording: " + method + " " + uri.getPath(),
                    client + ", HTTP " + call.status() + ", no network used. Answer: " + answer);
            return new Answer(call.status(), call.contentType(), answer.getBytes(StandardCharsets.UTF_8));
        }
        Answer answer = real.run();
        if (Session.current() instanceof RecordSession session) {
            // REG-8: the real call happened. It is recorded under the request id.
            HttpCall call = session.recordHttp(method, url, requestBody, answer.status(), answer.contentType(),
                    new String(answer.body(), StandardCharsets.UTF_8));
            regtests.events().emit("REG-8", OutboundHttp.class, "Outbound call captured: " + method + " " + uri.getPath(),
                    client + ", under request id " + session.requestId() + ", HTTP " + call.status()
                            + ". Answer: " + Json.bodyText(call.responseBody()));
        }
        return answer;
    }
}
