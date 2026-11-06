package example.regtests;

import example.regtests.OutboundHttp.Answer;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * The one line for a team on Apache HttpClient 5:
 * {@code builder.addExecInterceptorFirst("regtests", RegtestsApacheHttp.interceptor())}.
 * With REGTESTS_MODE=off it passes every call straight through.
 */
public final class RegtestsApacheHttp {

    private RegtestsApacheHttp() {
    }

    public static ExecChainHandler interceptor() {
        return (request, scope, chain) -> {
            if (!OutboundHttp.isOn()) {
                return chain.proceed(request, scope);
            }
            byte[] body = new byte[0];
            HttpEntity entity = request.getEntity();
            if (entity != null) {
                body = EntityUtils.toByteArray(entity);
                request.setEntity(new ByteArrayEntity(body, ContentType.parse(entity.getContentType())));
            }
            Answer answer;
            try {
                answer = OutboundHttp.call("Apache HttpClient", request.getMethod(), request.getUri(),
                        new String(body, StandardCharsets.UTF_8), () -> {
                            try (ClassicHttpResponse real = chain.proceed(request, scope)) {
                                return new Answer(real.getCode(), real.getEntity().getContentType(),
                                        EntityUtils.toByteArray(real.getEntity()));
                            } catch (HttpException e) {
                                throw new IOException(e);
                            }
                        });
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(answer.status());
            response.setHeader(HttpHeaders.CONTENT_TYPE, answer.contentType());
            response.setEntity(new ByteArrayEntity(answer.body(), ContentType.parse(answer.contentType())));
            return response;
        };
    }
}
