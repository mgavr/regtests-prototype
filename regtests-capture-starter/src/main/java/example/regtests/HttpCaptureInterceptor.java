package example.regtests;

import example.regtests.OutboundHttp.Answer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Hooks RestTemplate and RestClient into {@link OutboundHttp}. */
final class HttpCaptureInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Answer answer = OutboundHttp.call("RestTemplate or RestClient", request.getMethod().name(), request.getURI(),
                new String(body, StandardCharsets.UTF_8), () -> {
                    try (ClientHttpResponse real = execution.execute(request, body)) {
                        return new Answer(real.getStatusCode().value(),
                                Objects.toString(real.getHeaders().getContentType(), null),
                                StreamUtils.copyToByteArray(real.getBody()));
                    }
                });
        return new BufferedResponse(answer);
    }

    private record BufferedResponse(Answer answer) implements ClientHttpResponse {

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(answer.status());
        }

        @Override
        public String getStatusText() {
            return String.valueOf(answer.status());
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            if (answer.contentType() != null) {
                headers.set(HttpHeaders.CONTENT_TYPE, answer.contentType());
            }
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(answer.body());
        }

        @Override
        public void close() {
            // Nothing to close: the body is already in memory.
        }
    }
}
