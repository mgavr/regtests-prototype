package example.regtests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Base64;

/** JSON helpers. Decimals stay exact, so a number never drifts by a rounding error between record and replay. */
final class Json {

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private static final ObjectWriter COMPACT = MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    /** Turns a Java value from a JDBC call into the JSON form stored in a recording. */
    static JsonNode toNode(Object value) {
        if (value == null) {
            return NullNode.instance;
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof String text) {
            return TextNode.valueOf(text);
        }
        if (value instanceof Boolean flag) {
            return BooleanNode.valueOf(flag);
        }
        if (value instanceof BigDecimal decimal) {
            return DecimalNode.valueOf(decimal);
        }
        if (value instanceof BigInteger big) {
            return DecimalNode.valueOf(new BigDecimal(big));
        }
        if (value instanceof Float || value instanceof Double) {
            return DecimalNode.valueOf(new BigDecimal(value.toString()));
        }
        if (value instanceof Number number) {
            return LongNode.valueOf(number.longValue());
        }
        if (value instanceof Timestamp timestamp) {
            return TextNode.valueOf(timestamp.toLocalDateTime().toString());
        }
        if (value instanceof byte[] bytes) {
            return TextNode.valueOf(Base64.getEncoder().encodeToString(bytes));
        }
        return TextNode.valueOf(value.toString());
    }

    /** A message body as JSON when it parses, otherwise as plain text. An empty body is null. */
    static JsonNode parseBody(String text) {
        if (text == null || text.isBlank()) {
            return NullNode.instance;
        }
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(text);
        }
    }

    /** The reverse of {@link #parseBody}: what goes back on the wire in replay. */
    static String bodyText(JsonNode body) {
        if (isNull(body)) {
            return "";
        }
        return body.isTextual() ? body.asText() : compact(body);
    }

    static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    static String compact(JsonNode node) {
        try {
            return COMPACT.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write JSON", e);
        }
    }
}
