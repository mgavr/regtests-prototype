package example.regtests;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** The shape of a recording on disk. One case is one folder, and these records are its files. */
final class Model {

    private Model() {
    }

    /** inbound.json: what came in and what went back. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Inbound(@JsonProperty("case") String caseName, String requestId, String recordedAt,
                   InboundRequest request, InboundResponse response) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InboundRequest(String method, String path, String query, String contentType, JsonNode body) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InboundResponse(int status, String contentType, JsonNode body) {
    }

    /** One entry of outbound-http.json. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record HttpCall(int seq, String thread, String method, String url, JsonNode requestBody,
                    int status, String contentType, JsonNode responseBody) {
    }

    /**
     * One entry of sql.json: any statement or procedure call. {@code params} holds the IN values by
     * position, with null where a parameter is OUT. {@code out} holds what came back in OUT
     * parameters, and {@code results} every result set or update count, in order.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JdbcCall(int seq, String thread, String sql, JsonNode params, List<OutValue> out, List<Result> results) {
    }

    record OutValue(int position, String javaType, JsonNode value) {
    }

    /** Either a result set, with columns and rows, or an update count. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Result(List<SqlColumn> columns, JsonNode rows, Integer updateCount) {
    }

    record SqlColumn(String name, String javaType, int sqlType) {
    }

    /** A whole case, loaded for replay. */
    record RecordedCase(Inbound inbound, List<HttpCall> http, List<JdbcCall> jdbc) {
    }
}
