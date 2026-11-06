package example.regtests;

import example.regtests.Model.JdbcCall;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.SortedMap;
import java.util.TreeMap;

// REG-19: the fake database. DataSource, Connection and every kind of Statement are dynamic proxies, and all answers come from the recording. No driver sits behind them, so a connection cannot happen.
final class JdbcReplay {

    private JdbcReplay() {
    }

    static DataSource dataSource(Regtests regtests) {
        return Jdbc.proxy(DataSource.class, (proxy, method, args) -> {
            if (Jdbc.isObjectMethod(method)) {
                return Jdbc.objectMethod(proxy, method, args, "ReplayDataSource");
            }
            if (method.getName().equals("getConnection")) {
                return Jdbc.proxy(Connection.class, new ConnectionHandler(regtests));
            }
            throw notAvailable("DataSource", method);
        });
    }

    private static SQLFeatureNotSupportedException notAvailable(String type, Method method) {
        return new SQLFeatureNotSupportedException(
                "REPLAY: " + type + "." + method.getName() + " is not available. There is no database in replay.");
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Regtests regtests;
        private boolean closed;
        private boolean autoCommit = true;

        ConnectionHandler(Regtests regtests) {
            this.regtests = regtests;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (Jdbc.isObjectMethod(method)) {
                return Jdbc.objectMethod(proxy, method, args, "ReplayConnection");
            }
            switch (method.getName()) {
                case "prepareCall":
                    return Jdbc.proxy(CallableStatement.class, new StatementHandler((String) args[0], regtests));
                case "prepareStatement":
                    return Jdbc.proxy(PreparedStatement.class, new StatementHandler((String) args[0], regtests));
                case "createStatement":
                    return Jdbc.proxy(Statement.class, new StatementHandler(null, regtests));
                case "close":
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit":
                    autoCommit = (boolean) args[0];
                    return null;
                case "commit", "rollback", "clearWarnings", "getWarnings":
                    return null;
                default:
                    throw notAvailable("Connection", method);
            }
        }
    }

    private static final class StatementHandler implements InvocationHandler {

        private final String preparedSql;
        private final Regtests regtests;
        private final SortedMap<Integer, Object> params = new TreeMap<>();
        private Outcome outcome;

        StatementHandler(String preparedSql, Regtests regtests) {
            this.preparedSql = preparedSql;
            this.regtests = regtests;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (Jdbc.isObjectMethod(method)) {
                return Jdbc.objectMethod(proxy, method, args, "ReplayStatement");
            }
            String name = method.getName();
            if (Jdbc.noteParam(name, args, params)) {
                return null;
            }
            if (outcome != null && Outcome.isReadCall(name, args)) {
                return outcome.answer(name, args);
            }
            switch (name) {
                case "executeQuery":
                    outcome = answer(Jdbc.sqlOf(args, preparedSql));
                    return outcome.resultSet();
                case "executeUpdate":
                    outcome = answer(Jdbc.sqlOf(args, preparedSql));
                    return outcome.updateCount();
                case "execute":
                    outcome = answer(Jdbc.sqlOf(args, preparedSql));
                    return outcome.firstIsRows();
                case "registerOutParameter", "close", "setFetchSize", "setMaxRows", "setQueryTimeout",
                     "clearWarnings", "getWarnings":
                    return null;
                default:
                    throw notAvailable("Statement", method);
            }
        }

        private Outcome answer(String sql) {
            ReplaySession session = ReplaySession.require("database call \"" + sql + "\"");
            JdbcCall call = session.answerJdbc(sql, Jdbc.paramsAsJson(params));
            regtests.events().emit("REG-19", JdbcReplay.class, "Database call answered from the recording: " + SqlText.shortName(call.sql()),
                    "No connection made. On " + Thread.currentThread().getName() + ". OUT: " + Json.compact(Json.MAPPER.valueToTree(call.out()))
                            + " Results: " + Json.compact(Json.MAPPER.valueToTree(call.results())),
                    java.util.Map.of("sql", call.sql()));
            return Outcome.from(call);
        }
    }
}
