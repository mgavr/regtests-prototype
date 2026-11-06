package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import example.regtests.Model.Result;
import example.regtests.Model.SqlColumn;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A result set held in memory. In record it is filled from the real ResultSet, and in replay from
 * the recording. Either way the service reads it through the same proxy, so anything the proxy
 * cannot do shows up while recording, not on some later replay.
 */
final class Rows {

    private final List<SqlColumn> columns;
    private final List<Object[]> rows;

    private Rows(List<SqlColumn> columns, List<Object[]> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    static Rows drain(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        List<SqlColumn> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(new SqlColumn(meta.getColumnLabel(i), meta.getColumnClassName(i), meta.getColumnType(i)));
        }
        List<Object[]> rows = new ArrayList<>();
        while (resultSet.next()) {
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = resultSet.getObject(i + 1);
            }
            rows.add(row);
        }
        resultSet.close();
        return new Rows(columns, rows);
    }

    static Rows from(Result recorded) {
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode row : recorded.rows()) {
            Object[] values = new Object[recorded.columns().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = fromJson(row.get(i), recorded.columns().get(i).javaType());
            }
            rows.add(values);
        }
        return new Rows(recorded.columns(), rows);
    }

    List<SqlColumn> columns() {
        return columns;
    }

    ArrayNode asJson() {
        ArrayNode json = Json.MAPPER.createArrayNode();
        for (Object[] row : rows) {
            ArrayNode values = Json.MAPPER.createArrayNode();
            for (Object value : row) {
                values.add(Json.toNode(value));
            }
            json.add(values);
        }
        return json;
    }

    ResultSet asResultSet() {
        return Jdbc.proxy(ResultSet.class, new Cursor());
    }

    /** Back from the JSON form to the type the driver gave us. Anything unlisted stays text. */
    static Object fromJson(JsonNode node, String javaType) {
        if (Json.isNull(node)) {
            return null;
        }
        String text = node.asText();
        if (text.startsWith(Scrubber.PREFIX)) {
            return text;
        }
        return switch (javaType) {
            case "java.lang.Long" -> node.asLong();
            case "java.lang.Integer" -> node.asInt();
            case "java.lang.Double" -> node.asDouble();
            case "java.lang.Boolean" -> node.asBoolean();
            case "java.math.BigDecimal" -> new BigDecimal(text);
            case "java.sql.Timestamp" -> Timestamp.valueOf(LocalDateTime.parse(text));
            default -> text;
        };
    }

    /** One getter's worth of conversion, used for result-set columns and OUT parameters alike. */
    static Object read(String getter, Object value, Object[] args) throws SQLException {
        return switch (getter) {
            case "getObject" -> args.length == 2 ? as(value, (Class<?>) args[1]) : value;
            case "getString" -> value == null ? null : value.toString();
            case "getLong" -> number(value).longValue();
            case "getInt" -> number(value).intValue();
            case "getDouble" -> number(value).doubleValue();
            case "getBigDecimal" -> value == null ? null : new BigDecimal(value.toString());
            case "getBoolean" -> value instanceof Boolean flag ? flag : number(value).intValue() != 0;
            case "getTimestamp" -> value;
            default -> throw new SQLFeatureNotSupportedException(getter + " is not available on recorded values");
        };
    }

    private static Number number(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number : new BigDecimal(value.toString());
    }

    private static Object as(Object value, Class<?> type) throws SQLException {
        if (value == null || type.isInstance(value)) {
            return value;
        }
        if (value instanceof Timestamp timestamp && type == LocalDateTime.class) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Timestamp timestamp && type == Instant.class) {
            return timestamp.toInstant();
        }
        throw new SQLException("Cannot read recorded " + value.getClass().getName() + " as " + type.getName());
    }

    /** The ResultSet. It does what row mappers need and refuses the rest out loud. */
    private final class Cursor implements InvocationHandler {

        private int position = -1;
        private boolean closed;
        private boolean lastWasNull;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            if (Jdbc.isObjectMethod(method)) {
                return Jdbc.objectMethod(proxy, method, args, "Rows[" + rows.size() + "]");
            }
            String name = method.getName();
            switch (name) {
                case "next":
                    position++;
                    return position < rows.size();
                case "close":
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "wasNull":
                    return lastWasNull;
                case "getMetaData":
                    return Jdbc.proxy(ResultSetMetaData.class, new Metadata());
                case "findColumn":
                    return indexOf((String) args[0]);
                case "getType":
                    return ResultSet.TYPE_FORWARD_ONLY;
                case "getWarnings":
                    return null;
                default:
                    if (!name.startsWith("get") || args == null) {
                        throw new SQLFeatureNotSupportedException("ResultSet." + name + " is not available on recorded rows");
                    }
                    int index = args[0] instanceof Integer number ? number : indexOf((String) args[0]);
                    Object value = rows.get(position)[index - 1];
                    lastWasNull = value == null;
                    return read(name, value, args);
            }
        }

        private int indexOf(String label) throws SQLException {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equalsIgnoreCase(label)) {
                    return i + 1;
                }
            }
            throw new SQLException("No column named " + label + " in the recorded rows");
        }
    }

    private final class Metadata implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            if (Jdbc.isObjectMethod(method)) {
                return Jdbc.objectMethod(proxy, method, args, "RowsMetadata");
            }
            return switch (method.getName()) {
                case "getColumnCount" -> columns.size();
                case "getColumnLabel", "getColumnName" -> columns.get((int) args[0] - 1).name();
                case "getColumnClassName" -> columns.get((int) args[0] - 1).javaType();
                case "getColumnType" -> columns.get((int) args[0] - 1).sqlType();
                default -> throw new SQLFeatureNotSupportedException(
                        "ResultSetMetaData." + method.getName() + " is not available on recorded rows");
            };
        }
    }
}
