package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import example.regtests.Model.JdbcCall;
import example.regtests.Model.OutValue;
import example.regtests.Model.Result;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything one statement produced: result sets and update counts in order, plus OUT parameters.
 * Both modes hand the service one of these, so what it reads after {@code execute()} goes through
 * the same code whether the values just came from a database or from a file.
 */
final class Outcome {

    private final List<Object> results;
    private final Map<Integer, Object> out;
    private int index;
    private boolean lastWasNull;

    private Outcome(List<Object> results, Map<Integer, Object> out) {
        this.results = results;
        this.out = out;
    }

    /** Nothing at all, for a call the recording has no answer to. */
    static Outcome none() {
        return new Outcome(List.of(), Map.of());
    }

    /** A single result set or update count, from executeQuery or executeUpdate. */
    static Outcome of(Object result) {
        return new Outcome(List.of(result), Map.of());
    }

    /** Reads everything the real statement has, after a plain execute(). */
    static Outcome drain(Statement real, boolean firstIsRows, Set<Integer> outPositions) throws SQLException {
        List<Object> results = new ArrayList<>();
        boolean isRows = firstIsRows;
        while (true) {
            if (isRows) {
                results.add(Rows.drain(real.getResultSet()));
            } else {
                int count = real.getUpdateCount();
                if (count == -1) {
                    break;
                }
                results.add(count);
            }
            isRows = real.getMoreResults();
        }
        Map<Integer, Object> out = new TreeMap<>();
        for (int position : outPositions) {
            out.put(position, ((CallableStatement) real).getObject(position));
        }
        return new Outcome(results, out);
    }

    /** Rebuilds what was recorded. */
    static Outcome from(JdbcCall call) {
        List<Object> results = new ArrayList<>();
        for (Result result : call.results()) {
            results.add(result.rows() != null ? Rows.from(result) : result.updateCount());
        }
        Map<Integer, Object> out = new TreeMap<>();
        for (OutValue value : call.out()) {
            out.put(value.position(), Rows.fromJson(value.value(), value.javaType()));
        }
        return new Outcome(results, out);
    }

    /** True for the calls that read a finished statement, which are answered by {@link #answer}. */
    static boolean isReadCall(String name, Object[] args) {
        return switch (name) {
            case "getResultSet", "getMoreResults", "getUpdateCount", "wasNull" -> true;
            default -> name.startsWith("get") && args != null && args[0] instanceof Integer;
        };
    }

    Object answer(String name, Object[] args) throws SQLException {
        return switch (name) {
            case "getResultSet" -> resultSet();
            case "getMoreResults" -> moreResults();
            case "getUpdateCount" -> updateCount();
            case "wasNull" -> lastWasNull;
            default -> {
                Object value = out.get((Integer) args[0]);
                lastWasNull = value == null;
                yield Rows.read(name, value, args);
            }
        };
    }

    boolean firstIsRows() {
        return current() instanceof Rows;
    }

    ResultSet resultSet() {
        return current() instanceof Rows rows ? rows.asResultSet() : null;
    }

    int updateCount() {
        return current() instanceof Integer count ? count : -1;
    }

    private boolean moreResults() {
        index++;
        return firstIsRows();
    }

    private Object current() {
        return index < results.size() ? results.get(index) : null;
    }

    /** OUT parameters as they go to disk, masked by position. */
    List<OutValue> outValues(String sql, Scrubber scrubber) {
        List<OutValue> values = new ArrayList<>();
        out.forEach((position, value) -> {
            JsonNode masked = scrubber.param(sql, position, Json.toNode(value));
            values.add(new OutValue(position, value == null ? null : value.getClass().getName(), masked));
        });
        return values;
    }

    /** Result sets and update counts as they go to disk, with rows masked by column. */
    List<Result> resultValues(Scrubber scrubber) {
        List<Result> values = new ArrayList<>();
        for (Object result : results) {
            if (result instanceof Rows rows) {
                var masked = Json.MAPPER.createArrayNode();
                rows.asJson().forEach(row -> masked.add(scrubber.row(rows.columns(), row)));
                values.add(new Result(rows.columns(), masked, null));
            } else {
                values.add(new Result(null, null, (Integer) result));
            }
        }
        return values;
    }
}
