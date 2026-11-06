package example.regtests;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim that the DataSource is the common denominator, tested: JdbcTemplate and JPA each
 * record against a real H2 database, then replay with no database at all, through the same hook.
 */
class JdbcStacksTest {

    @Entity(name = "StackThing")
    public static class StackThing {
        @Id
        public String id;
        public String label;
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = StackThing.class)
    static class Entities {
        @PersistenceContext
        EntityManager entityManager;
    }

    @TempDir
    Path recordings;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegtestsAutoConfiguration.class, DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(Entities.class)
            .withPropertyValues("spring.jpa.database-platform=org.hibernate.dialect.H2Dialect");

    @AfterEach
    void cleanUp() {
        Session.unbind();
        OutboundHttp.activate(null);
    }

    private ApplicationContextRunner recording() {
        return runner.withPropertyValues("regtests.mode=record", "regtests.recordings=" + recordings,
                "spring.datasource.url=jdbc:h2:mem:stacks;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop");
    }

    private ApplicationContextRunner replaying() {
        return runner.withPropertyValues("regtests.mode=replay", "regtests.recordings=" + recordings,
                "spring.datasource.url=jdbc:h2:tcp://127.0.0.1:1/never", "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false");
    }

    private void record(Regtests regtests, Runnable work) {
        RecordSession session = new RecordSession("id", "stack-case", regtests.scrubber());
        Session.bind(session);
        work.run();
        session.captureInbound(new Model.InboundRequest("GET", "/x", null, null, Json.parseBody("")), 200, null, "");
        session.save(regtests.files());
        Session.unbind();
    }

    private ReplayReport replay(Regtests regtests, Runnable work) {
        ReplaySession session = new ReplaySession("run", regtests.files().load("stack-case"), regtests.scrubber(), regtests.comparer());
        Session.bind(session);
        work.run();
        Session.unbind();
        return session.report(false);
    }

    @Test
    void jdbcTemplateRecordsAndReplays() {
        String[] recorded = new String[1];
        String insert = "INSERT INTO stack_plain (id, label) VALUES (?, ?)";
        String select = "SELECT label FROM stack_plain WHERE id = ?";
        recording().run(context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE stack_plain (id VARCHAR(10) PRIMARY KEY, label VARCHAR(10))");
            record(context.getBean(Regtests.class), () -> {
                jdbc.update(insert, "a", "first");
                recorded[0] = jdbc.queryForObject(select, String.class, "a");
            });
        });
        assertThat(recorded[0]).isEqualTo("first");

        replaying().run(context -> {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String[] replayed = new String[1];
            ReplayReport report = replay(context.getBean(Regtests.class), () -> {
                jdbc.update(insert, "a", "first");
                replayed[0] = jdbc.queryForObject(select, String.class, "a");
            });
            assertThat(replayed[0]).isEqualTo("first");
            assertThat(report.differences()).isEmpty();
            assertThat(report.missingCalls()).isEmpty();
        });
    }

    @Test
    void jpaRecordsAndReplays() {
        recording().run(context -> {
            assertThat(context).hasNotFailed();
            record(context.getBean(Regtests.class), () -> saveAndFind(context.getBean(TransactionTemplate.class),
                    context.getBean(Entities.class).entityManager));
        });

        replaying().run(context -> {
            assertThat(context).hasNotFailed();
            ReplayReport report = replay(context.getBean(Regtests.class), () -> {
                StackThing found = saveAndFind(context.getBean(TransactionTemplate.class),
                        context.getBean(Entities.class).entityManager);
                assertThat(found.label).isEqualTo("first");
            });
            assertThat(report.differences()).isEmpty();
            assertThat(report.missingCalls()).isEmpty();
        });
    }

    private static StackThing saveAndFind(TransactionTemplate transaction, EntityManager entityManager) {
        transaction.executeWithoutResult(status -> {
            StackThing thing = new StackThing();
            thing.id = "a";
            thing.label = "first";
            entityManager.persist(thing);
        });
        return transaction.execute(status -> entityManager.find(StackThing.class, "a"));
    }
}
