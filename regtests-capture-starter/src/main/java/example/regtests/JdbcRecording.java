package example.regtests;

import example.regtests.Model.JdbcCall;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Record-mode JDBC: real calls to the real database, with a copy of each one kept. */
final class JdbcRecording {

    private JdbcRecording() {
    }

    static DataSource wrap(DataSource real, Regtests regtests) {
        return new CapturingDataSource(real, regtests);
    }

    private static final class CapturingDataSource extends DelegatingDataSource implements AutoCloseable {

        private final Regtests regtests;

        CapturingDataSource(DataSource real, Regtests regtests) {
            super(real);
            this.regtests = regtests;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = super.getConnection();
            return Jdbc.proxy(Connection.class, (proxy, method, args) -> {
                Object result = call(real, method, args);
                return switch (method.getName()) {
                    case "prepareCall" -> Jdbc.proxy(CallableStatement.class, new StatementHandler((Statement) result, (String) args[0], regtests));
                    case "prepareStatement" -> Jdbc.proxy(PreparedStatement.class, new StatementHandler((Statement) result, (String) args[0], regtests));
                    case "createStatement" -> Jdbc.proxy(Statement.class, new StatementHandler((Statement) result, null, regtests));
                    default -> result;
                };
            });
        }

        /** Lets the pool shut down when the application context closes. */
        @Override
        public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static final class StatementHandler implements InvocationHandler {

        private final Statement real;
        private final String preparedSql;
        private final Regtests regtests;
        private final SortedMap<Integer, Object> params = new TreeMap<>();
        private final SortedSet<Integer> outPositions = new TreeSet<>();
        private Outcome outcome;

        StatementHandler(Statement real, String preparedSql, Regtests regtests) {
            this.real = real;
            this.preparedSql = preparedSql;
            this.regtests = regtests;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Jdbc.noteParam(name, args, params);
            if (name.equals("registerOutParameter")) {
                outPositions.add((Integer) args[0]);
            }
            if (outcome != null && Outcome.isReadCall(name, args)) {
                return outcome.answer(name, args);
            }
            String sql = Jdbc.sqlOf(args, preparedSql);
            switch (name) {
                case "executeQuery":
                    outcome = Outcome.of(Rows.drain((ResultSet) call(real, method, args)));
                    capture(sql);
                    return outcome.resultSet();
                case "executeUpdate":
                    int count = (int) call(real, method, args);
                    outcome = Outcome.of(count);
                    capture(sql);
                    return count;
                case "execute":
                    boolean firstIsRows = (boolean) call(real, method, args);
                    outcome = Outcome.drain(real, firstIsRows, outPositions);
                    capture(sql);
                    return firstIsRows;
                default:
                    return call(real, method, args);
            }
        }

        // REG-7: the real database call ran. Its SQL or procedure, IN and OUT parameters, rows and counts are stored under the request id, masked first.
        private void capture(String sql) {
            if (Session.current() instanceof RecordSession session) {
                JdbcCall call = session.recordJdbc(sql, Jdbc.paramsAsJson(params), outcome);
                regtests.events().emit("REG-7", JdbcRecording.class, "Database call captured: " + SqlText.shortName(call.sql()),
                        "Under request id " + session.requestId() + " on " + Thread.currentThread().getName() + ". IN: "
                                + Json.compact(call.params()) + " OUT: " + Json.compact(Json.MAPPER.valueToTree(call.out()))
                                + " Results: " + Json.compact(Json.MAPPER.valueToTree(call.results())),
                        java.util.Map.of("sql", call.sql()));
            }
        }
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
