package example.regtests;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * regtests.json, an optional file that sits with the recordings. It says what may differ between
 * runs and what counts as personal data, with no code changes. SQL rules are keyed by any text
 * found in the statement, such as a procedure name, and name parameters by position, counting from 1.
 */
final class ServiceConfig {

    static final String FILE_NAME = "regtests.json";

    private static final List<String> COMMON_PERSONAL_FIELDS =
            List.of("fullName", "firstName", "lastName", "email", "phone", "address", "dateOfBirth");

    record Normalize(boolean timestamps, boolean uuids) {
    }

    record Ignore(List<String> jsonPaths, Map<String, List<Integer>> sqlParams) {
    }

    record Personal(List<String> fields, Map<String, List<Integer>> sqlParams) {
    }

    private final Normalize normalize;
    private final Ignore ignore;
    private final Personal personal;

    @JsonCreator
    ServiceConfig(@JsonProperty("normalize") Normalize normalize, @JsonProperty("ignore") Ignore ignore,
                  @JsonProperty("personal") Personal personal) {
        this.normalize = normalize == null ? new Normalize(true, true) : normalize;
        this.ignore = ignore == null ? new Ignore(List.of(), Map.of()) : ignore;
        this.personal = personal == null ? new Personal(List.of(), Map.of()) : personal;
    }

    static ServiceConfig load(Path recordings) {
        Path file = recordings.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new ServiceConfig(null, null, null);
        }
        try {
            return Json.MAPPER.readValue(file.toFile(), ServiceConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    Normalize normalize() {
        return normalize;
    }

    List<String> ignoredJsonPaths() {
        return ignore.jsonPaths() == null ? List.of() : ignore.jsonPaths();
    }

    List<Integer> ignoredParams(String sql) {
        return positions(ignore.sqlParams(), sql);
    }

    /** Common names are masked with no config at all. The file adds to them. */
    List<String> personalFields() {
        List<String> fields = new ArrayList<>(COMMON_PERSONAL_FIELDS);
        if (personal.fields() != null) {
            fields.addAll(personal.fields());
        }
        return fields;
    }

    List<Integer> personalParams(String sql) {
        return positions(personal.sqlParams(), sql);
    }

    private static List<Integer> positions(Map<String, List<Integer>> rules, String sql) {
        if (rules == null) {
            return List.of();
        }
        String statement = sql.toLowerCase(Locale.ROOT);
        return rules.entrySet().stream()
                .filter(rule -> statement.contains(rule.getKey().toLowerCase(Locale.ROOT)))
                .flatMap(rule -> rule.getValue().stream())
                .toList();
    }
}
