package example.regtests;

import com.fasterxml.jackson.annotation.JsonProperty;
import example.regtests.Comparer.Difference;

import java.util.List;

/**
 * The verdict for one replayed request, served as JSON to the test that asked for it.
 * Anything in {@code differences}, {@code unexpectedCalls} or {@code missingCalls} fails the case.
 */
record ReplayReport(@JsonProperty("case") String caseName, String runId, boolean passed, int status,
                    int callsCompared, List<Difference> differences, List<String> unexpectedCalls,
                    List<String> missingCalls, String serviceError, boolean timedOut) {
}
