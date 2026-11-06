package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

// REG-23: the comparison. What the new build did is held against the recording, field by field. ISO timestamps and UUIDs are normalised first, then the service's config file says what else may differ.
final class Comparer {

    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String ABSENT = "(absent)";

    private final ServiceConfig config;

    Comparer(ServiceConfig config) {
        this.config = config;
    }

    /** One difference, written so a person can read it without opening a file. */
    record Difference(String where, String path, String expected, String actual) {
    }

    List<Difference> compareJson(String where, JsonNode expected, JsonNode actual) {
        List<Difference> differences = new ArrayList<>();
        walk(where, "$", expected, actual, differences);
        return differences;
    }

    /** Parameters are compared by position, skipping the ones the config file names for this statement. */
    List<Difference> compareParams(String sql, JsonNode expected, JsonNode actual) {
        List<Difference> differences = new ArrayList<>();
        List<Integer> ignored = config.ignoredParams(sql);
        String where = "Database call " + SqlText.shortName(sql);
        for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            if (!ignored.contains(i + 1) && !same(expected.get(i), actual.get(i))) {
                differences.add(new Difference(where, "parameter " + (i + 1), show(expected.get(i)), show(actual.get(i))));
            }
        }
        return differences;
    }

    Difference compareText(String where, String path, String expected, String actual) {
        return normalize(String.valueOf(expected)).equals(normalize(String.valueOf(actual)))
                ? null
                : new Difference(where, path, String.valueOf(expected), String.valueOf(actual));
    }

    private void walk(String where, String path, JsonNode expected, JsonNode actual, List<Difference> out) {
        if (config.ignoredJsonPaths().contains(path.replaceAll("\\[\\d+]", "[*]"))
                || config.ignoredJsonPaths().contains(path)) {
            return;
        }
        if (expected != null && actual != null && expected.isObject() && actual.isObject()) {
            Set<String> names = new TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> walk(where, path + "." + name, expected.get(name), actual.get(name), out));
        } else if (expected != null && actual != null && expected.isArray() && actual.isArray()) {
            for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
                walk(where, path + "[" + i + "]", expected.get(i), actual.get(i), out);
            }
        } else if (!same(expected, actual)) {
            out.add(new Difference(where, path, show(expected), show(actual)));
        }
    }

    private boolean same(JsonNode expected, JsonNode actual) {
        if (Json.isNull(expected) || Json.isNull(actual)) {
            return Json.isNull(expected) && Json.isNull(actual);
        }
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.isTextual() && actual.isTextual()) {
            return normalize(expected.asText()).equals(normalize(actual.asText()));
        }
        return expected.equals(actual);
    }

    private String normalize(String text) {
        String result = text;
        if (config.normalize().timestamps()) {
            result = TIMESTAMP.matcher(result).replaceAll("<timestamp>");
        }
        if (config.normalize().uuids()) {
            result = UUID.matcher(result).replaceAll("<uuid>");
        }
        return result;
    }

    private static String show(JsonNode node) {
        return Json.isNull(node) ? ABSENT : Json.compact(node);
    }
}
