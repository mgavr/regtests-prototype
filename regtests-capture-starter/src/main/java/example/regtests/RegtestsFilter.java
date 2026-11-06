package example.regtests;

import example.regtests.Comparer.Difference;
import example.regtests.Model.InboundRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The front door. Inbound traffic only: every request passes through here first and last. */
final class RegtestsFilter extends OncePerRequestFilter {

    static final String CASE_HEADER = "X-Regtests-Case";
    static final String RUN_HEADER = "X-Regtests-Run";
    static final String REPORT_PATH = "/__regtests/report/";

    private final Regtests regtests;
    private final Map<String, CompletableFuture<ReplayReport>> reports = new ConcurrentHashMap<>();

    RegtestsFilter(Regtests regtests) {
        this.regtests = regtests;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (regtests.mode() == RegtestsMode.REPLAY) {
            replay(request, response, chain);
        } else {
            record(request, response, chain);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // REG-3: a request arrives. It gets a request id, held in thread context, and everything it touches from here on is saved under that id.
        String requestId = newId();
        String header = request.getHeader(CASE_HEADER);
        if (header != null && !CaseFiles.isSafeName(header)) {
            refuse(response, HttpServletResponse.SC_BAD_REQUEST, "Not a usable case name: " + header);
            return;
        }
        String caseName = header == null ? "case-" + requestId : header;
        RecordSession session = new RecordSession(requestId, caseName, regtests.scrubber());
        Session.bind(session);
        Events events = regtests.events();
        events.emit("REG-3", RegtestsFilter.class, "Request arrived: " + request.getMethod() + " " + request.getRequestURI(),
                "Case name: " + caseName);
        events.emit("REG-3", RegtestsFilter.class, "Request id stamped: " + requestId,
                "Held in thread context on " + Thread.currentThread().getName() + ".");

        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        boolean failed = true;
        try {
            chain.doFilter(cachedRequest, cachedResponse);
            failed = false;
        } finally {
            // REG-12: the response is captured, masked like everything else, then released to the caller.
            int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : cachedResponse.getStatus();
            InboundRequest inbound = new InboundRequest(request.getMethod(), request.getRequestURI(),
                    request.getQueryString(), request.getContentType(),
                    Json.parseBody(new String(cachedRequest.getContentAsByteArray(), StandardCharsets.UTF_8)));
            session.captureInbound(inbound, status, cachedResponse.getContentType(), bodyOf(cachedResponse));
            events.emit("REG-11", Scrubber.class, "Personal data masked: " + session.maskedSoFar() + " values",
                    "Masked at capture, so the real values never reach disk. Same input, same mask, in traffic and rows.");
            cachedResponse.copyBodyToResponse();
            events.emit("REG-12", RegtestsFilter.class, "Response returned: HTTP " + status,
                    "The caller has its answer. The case stays open until any @Async work is done.");
            Session.unbind();
            session.responseFinished();
            saveWhenQuiet(session);
        }
    }

    private void saveWhenQuiet(RecordSession session) {
        session.whenQuiet()
                .orTimeout(regtests.asyncWait().toSeconds(), TimeUnit.SECONDS)
                .whenComplete((done, timeout) -> {
                    Path folder = session.save(regtests.files());
                    regtests.events().emit("REG-13", CaseFiles.class, "Case saved: " + session.caseName(),
                            folder + " holds " + String.join(", ",
                                    List.of(CaseFiles.INBOUND, CaseFiles.OUTBOUND_HTTP, CaseFiles.SQL))
                                    + (timeout == null ? "." : ". Saved early: @Async work did not finish in time."));
                });
    }

    private void replay(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith(REPORT_PATH)) {
            serveReport(request.getRequestURI().substring(REPORT_PATH.length()), response);
            return;
        }
        // REG-17: in replay every request must name its case. No header, or an unknown case, is refused here. It never reaches the service.
        String caseName = request.getHeader(CASE_HEADER);
        if (caseName == null) {
            refuse(response, HttpServletResponse.SC_BAD_REQUEST, "REPLAY: every request needs the " + CASE_HEADER + " header.");
            return;
        }
        if (!regtests.files().exists(caseName)) {
            refuse(response, HttpServletResponse.SC_NOT_FOUND, "REPLAY: no recorded case named " + caseName);
            return;
        }

        String runId = newId();
        ReplaySession session = new ReplaySession(runId, regtests.files().load(caseName), regtests.scrubber(), regtests.comparer());
        CompletableFuture<ReplayReport> report = new CompletableFuture<>();
        reports.put(runId, report);
        response.setHeader(RUN_HEADER, runId);
        Session.bind(session);
        Events events = regtests.events();
        events.emit("REG-17", RegtestsFilter.class, "Case loaded: " + caseName,
                "Picked by the " + CASE_HEADER + " header. Run id " + runId + ".");

        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        boolean failed = true;
        try {
            chain.doFilter(request, cachedResponse);
            failed = false;
        } catch (ServletException | IOException | RuntimeException e) {
            session.noteServiceError(e);
            throw e;
        } finally {
            int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : cachedResponse.getStatus();
            List<Difference> found = session.compareResponse(status, bodyOf(cachedResponse));
            events.emit("REG-23", Comparer.class,
                    found.isEmpty() ? "Response compared: identical" : "Response compared: " + found.size() + " difference(s)",
                    "HTTP " + status + ", checked against inbound.json.");
            cachedResponse.copyBodyToResponse();
            Session.unbind();
            session.responseFinished();
            finishReportWhenQuiet(session, report);
        }
    }

    private void finishReportWhenQuiet(ReplaySession session, CompletableFuture<ReplayReport> report) {
        session.whenQuiet()
                .orTimeout(regtests.asyncWait().toSeconds(), TimeUnit.SECONDS)
                .whenComplete((done, timeout) -> {
                    ReplayReport finished = session.report(timeout != null);
                    regtests.events().emit("REG-23", Comparer.class,
                            "Attempted calls compared: " + finished.callsCompared() + " calls, "
                                    + finished.differences().size() + " difference(s) in all",
                            "Database calls by procedure and parameters, outbound calls by URL and body. Unexpected: "
                                    + finished.unexpectedCalls().size() + ". Never made: " + finished.missingCalls().size() + ".");
                    report.complete(finished);
                });
    }

    private void serveReport(String runId, HttpServletResponse response) throws IOException {
        CompletableFuture<ReplayReport> report = reports.get(runId);
        if (report == null) {
            refuse(response, HttpServletResponse.SC_NOT_FOUND, "REPLAY: no run with id " + runId);
            return;
        }
        try {
            sendJson(response, HttpServletResponse.SC_OK, report.get(regtests.asyncWait().toSeconds() + 5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            refuse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "REPLAY: interrupted");
        } catch (ExecutionException | TimeoutException e) {
            refuse(response, HttpServletResponse.SC_GATEWAY_TIMEOUT, "REPLAY: the report for " + runId + " is not ready");
        }
    }

    private static void refuse(HttpServletResponse response, int status, String message) throws IOException {
        sendJson(response, status, Map.of("error", message));
    }

    private static void sendJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(Json.MAPPER.writeValueAsString(body));
    }

    private static String bodyOf(ContentCachingResponseWrapper response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
