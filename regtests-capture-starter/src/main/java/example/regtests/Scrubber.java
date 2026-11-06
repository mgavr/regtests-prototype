package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import example.regtests.Model.SqlColumn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

// REG-11: personal data is masked at capture and never reaches disk. One mask for traffic and rows: the same input always gives the same output, wherever it appears.
final class Scrubber {

    static final String PREFIX = "masked:";

    private final String salt;
    private final ServiceConfig config;
    private final Set<String> personal;

    Scrubber(String salt, ServiceConfig config) {
        this.salt = salt;
        this.config = config;
        this.personal = config.personalFields().stream().map(Scrubber::canonical).collect(Collectors.toSet());
    }

    /** "full_name", "FULL_NAME" and "fullName" are one field, so one rule covers JSON and SQL columns. */
    private static String canonical(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /** Deterministic and idempotent: masking a masked value changes nothing. Replay depends on both. */
    String mask(String value) {
        if (value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + value).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Masks JSON fields by name, at any depth. */
    JsonNode json(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = Json.MAPPER.createObjectNode();
            node.fields().forEachRemaining(field -> copy.set(field.getKey(), named(field.getKey(), field.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = Json.MAPPER.createArrayNode();
            node.forEach(item -> copy.add(json(item)));
            return copy;
        }
        return node;
    }

    /** Masks a result-set row by column name. */
    ArrayNode row(List<SqlColumn> columns, JsonNode row) {
        ArrayNode copy = Json.MAPPER.createArrayNode();
        for (int i = 0; i < row.size(); i++) {
            copy.add(named(columns.get(i).name(), row.get(i)));
        }
        return copy;
    }

    /** Masks a parameter by position, as named in the service's config file. */
    JsonNode param(String sql, int position, JsonNode value) {
        boolean isPersonal = config.personalParams(sql).contains(position);
        return isPersonal && !Json.isNull(value) ? TextNode.valueOf(mask(value.asText())) : value;
    }

    ArrayNode params(String sql, JsonNode params) {
        ArrayNode copy = Json.MAPPER.createArrayNode();
        for (int i = 0; i < params.size(); i++) {
            copy.add(param(sql, i + 1, params.get(i)));
        }
        return copy;
    }

    private JsonNode named(String name, JsonNode value) {
        if (personal.contains(canonical(name)) && value.isValueNode() && !value.isNull()) {
            return TextNode.valueOf(mask(value.asText()));
        }
        return json(value);
    }

    /** How many masked values a captured tree holds, for the live view. */
    static int countMasked(JsonNode node) {
        if (node.isTextual()) {
            return node.asText().startsWith(PREFIX) ? 1 : 0;
        }
        int total = 0;
        for (JsonNode child : node) {
            total += countMasked(child);
        }
        return total;
    }
}
