package example.regtests;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * The personal section of regtests.json, an optional file that sits with the recordings. The rest
 * of that file is comparison rules, which belong to the test runner, so they are skipped here. SQL
 * rules are keyed by any text found in the statement and name parameters by position, from 1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class ServiceConfig {

    static final String FILE_NAME = "regtests.json";

    private static final List<String> COMMON_PERSONAL_FIELDS =
            List.of("fullName", "firstName", "lastName", "email", "phone", "address", "dateOfBirth");

    record Personal(List<String> fields, Map<String, List<Integer>> sqlParams) {
    }

    private final Personal personal;

    @JsonCreator
    ServiceConfig(@JsonProperty("personal") Personal personal) {
        this.personal = personal == null ? new Personal(List.of(), Map.of()) : personal;
    }

    static ServiceConfig load(Path recordings) {
        Path file = recordings.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new ServiceConfig(null);
        }
        try {
            return Json.MAPPER.readValue(file.toFile(), ServiceConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
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
        if (personal.sqlParams() == null) {
            return List.of();
        }
        String statement = sql.toLowerCase(Locale.ROOT);
        return personal.sqlParams().entrySet().stream()
                .filter(rule -> statement.contains(rule.getKey().toLowerCase(Locale.ROOT)))
                .flatMap(rule -> rule.getValue().stream())
                .toList();
    }
}
