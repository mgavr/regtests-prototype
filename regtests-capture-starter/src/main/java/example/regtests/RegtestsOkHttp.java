package example.regtests;

import example.regtests.OutboundHttp.Answer;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import java.io.IOException;

/**
 * The one line for a team on OkHttp: {@code builder.addInterceptor(RegtestsOkHttp.interceptor())}.
 * With REGTESTS_MODE=off it passes every call straight through.
 */
public final class RegtestsOkHttp {

    private RegtestsOkHttp() {
    }

    public static Interceptor interceptor() {
        return chain -> {
            Request request = chain.request();
            if (!OutboundHttp.isOn()) {
                return chain.proceed(request);
            }
            Answer answer = OutboundHttp.call("OkHttp", request.method(), request.url().uri(), bodyOf(request), () -> {
                try (Response real = chain.proceed(request)) {
                    return new Answer(real.code(), real.header("Content-Type"), real.body().bytes());
                }
            });
            MediaType type = answer.contentType() == null ? null : MediaType.parse(answer.contentType());
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.status())
                    .message("")
                    .body(ResponseBody.create(answer.body(), type))
                    .build();
        };
    }

    private static String bodyOf(Request request) throws IOException {
        if (request.body() == null) {
            return "";
        }
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }
}
