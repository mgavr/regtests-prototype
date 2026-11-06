package example.regtests;

import example.regtests.Model.JdbcCall;
import example.regtests.Model.OutValue;
import example.regtests.Model.Result;
import example.regtests.Model.SqlColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fake database: no driver and no server anywhere in these tests. */
class JdbcReplayTest {

    private static final String CALL = "{call get_thing(?, ?)}";
    private static final String INSERT = "INSERT INTO thing (id, name) VALUES (?, ?)";

    @TempDir
    Path recordings;

    @AfterEach
    void cleanUp() {
        Session.unbind();
        OutboundHttp.activate(null);
    }

    private static JdbcCall procedureCall() {
        Result rows = new Result(List.of(new SqlColumn("NAME", "java.lang.String", Types.VARCHAR)),
                Json.MAPPER.createArrayNode().add(Fixtures.json("first")), null);
        return new JdbcCall(1, "t", CALL, Fixtures.json(7, null),
                List.of(new OutValue(2, "java.lang.Integer", Fixtures.json(42).get(0))),
                List.of(rows, new Result(null, null, 0)));
    }

    private static JdbcCall insertCall(int seq, Object id) {
        return new JdbcCall(seq, "t", INSERT, Fixtures.json(id, "a"), List.of(), List.of(new Result(null, null, 1)));
    }

    @Test
    void callableStatementAnswersInOutAndResultSetFromTheRecording() throws SQLException {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        ReplaySession session = Fixtures.replay(regtests, List.of(), List.of(procedureCall()));
        Session.bind(session);

        try (Connection connection = JdbcReplay.dataSource(regtests).getConnection();
             CallableStatement call = connection.prepareCall(CALL)) {
            call.setLong(1, 7);
            call.registerOutParameter(2, Types.INTEGER);

            assertThat(call.execute()).isTrue();
            ResultSet rows = call.getResultSet();
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("name")).isEqualTo("first");
            assertThat(rows.next()).isFalse();
            assertThat(call.getMoreResults()).isFalse();
            assertThat(call.getUpdateCount()).isZero();
            assertThat(call.getMoreResults()).isFalse();
            assertThat(call.getUpdateCount()).isEqualTo(-1);
            assertThat(call.getInt(2)).isEqualTo(42);
            assertThat(call.wasNull()).isFalse();
        }
        assertThat(session.jdbcCalls()).singleElement().satisfies(written -> assertThat(written.out()).hasSize(1));
    }

    @Test
    void aChangedInParameterIsStillAnsweredAndWrittenDownAsItWas() throws SQLException {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        ReplaySession session = Fixtures.replay(regtests, List.of(), List.of(procedureCall()));
        Session.bind(session);

        try (Connection connection = JdbcReplay.dataSource(regtests).getConnection();
             CallableStatement call = connection.prepareCall(CALL)) {
            call.setLong(1, 8);
            assertThat(call.execute()).isTrue();
        }
        assertThat(session.jdbcCalls()).singleElement()
                .satisfies(written -> assertThat(written.params().get(0).asInt()).isEqualTo(8));
    }

    @Test
    void plainSqlThroughJdbcTemplateUsesTheSameFake() {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        ReplaySession session = Fixtures.replay(regtests, List.of(), List.of(insertCall(1, 1)));
        Session.bind(session);

        int changed = new JdbcTemplate(JdbcReplay.dataSource(regtests)).update(INSERT, 1, "a");

        assertThat(changed).isEqualTo(1);
        assertThat(session.jdbcCalls()).singleElement()
                .satisfies(written -> assertThat(written.sql()).isEqualTo(INSERT));
    }

    @Test
    void anUnexpectedCallFailsLoudlyAndIsWrittenDownWithNoAnswer() {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        ReplaySession session = Fixtures.replay(regtests, List.of(), List.of());
        Session.bind(session);
        JdbcTemplate jdbc = new JdbcTemplate(JdbcReplay.dataSource(regtests));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM thing"))
                .isInstanceOf(UnexpectedCallException.class)
                .hasMessageContaining("DELETE FROM thing");
        assertThat(session.jdbcCalls()).singleElement()
                .satisfies(written -> assertThat(written.results()).isEmpty());
    }

    @Test
    void aRunIsWrittenInTheRecordingFormatUnderItsRunId() {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        ReplaySession session = Fixtures.replay(regtests, List.of(), List.of(insertCall(1, 1)));
        Session.bind(session);
        new JdbcTemplate(JdbcReplay.dataSource(regtests)).update(INSERT, 1, "a");
        session.captureInbound(new Model.InboundRequest("POST", "/x", null, null, Json.parseBody("")), 200, null, "{}");
        session.noteError("it broke");

        session.save(regtests.runs());

        Model.RecordedCase written = regtests.runs().load("run-1");
        assertThat(written.inbound().caseName()).isEqualTo("a-case");
        assertThat(written.inbound().error()).isEqualTo("it broke");
        assertThat(written.jdbc()).singleElement().satisfies(call -> assertThat(call.sql()).isEqualTo(INSERT));
    }

    @Test
    void outsideARequestNothingIsAnswered() {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        DataSource fake = JdbcReplay.dataSource(regtests);

        assertThatThrownBy(() -> new JdbcTemplate(fake).update(INSERT, 1, "a")).isInstanceOf(UnexpectedCallException.class);
    }

    @Test
    void callsOnOtherThreadsFindTheirAnswersInAnyOrder() {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        JdbcCall other = new JdbcCall(2, "t", "UPDATE thing SET name = ?", Fixtures.json("b"), List.of(), List.of(new Result(null, null, 3)));
        ReplaySession session = Fixtures.replay(regtests, List.of(), List.of(insertCall(1, 1), other));
        Session.bind(session);
        JdbcTemplate jdbc = new JdbcTemplate(JdbcReplay.dataSource(regtests));

        int updated = CompletableFuture.supplyAsync(() -> {
            Session.bind(session);
            try {
                return jdbc.update("UPDATE thing SET name = ?", "b");
            } finally {
                Session.unbind();
            }
        }).join();
        int inserted = jdbc.update(INSERT, 1, "a");

        assertThat(updated).isEqualTo(3);
        assertThat(inserted).isEqualTo(1);
        assertThat(session.jdbcCalls()).hasSize(2);
    }

    @Test
    void anOutValueOfNullReadsAsNull() throws SQLException {
        Regtests regtests = Fixtures.regtests(RegtestsMode.REPLAY, recordings);
        JdbcCall call = new JdbcCall(1, "t", CALL, Fixtures.json(7, null),
                List.of(new OutValue(2, null, Json.toNode(null))),
                List.of(new Result(null, null, 0)));
        Session.bind(Fixtures.replay(regtests, List.of(), List.of(call)));

        try (Connection connection = JdbcReplay.dataSource(regtests).getConnection();
             CallableStatement statement = connection.prepareCall(CALL)) {
            statement.setLong(1, 7);
            statement.execute();
            assertThat(statement.getString(2)).isNull();
            assertThat(statement.wasNull()).isTrue();
        }
    }
}
