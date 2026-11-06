package example.regtests;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * The database hook sits here, at the DataSource, on purpose. MyBatis, JPA and JdbcTemplate all end
 * up calling JDBC, so this one layer serves them all and nothing above it needs to be known.
 */
final class DataSourcePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<Regtests> regtests;

    DataSourcePostProcessor(ObjectProvider<Regtests> regtests) {
        this.regtests = regtests;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource real)) {
            return bean;
        }
        Regtests active = regtests.getObject();
        if (active.mode() == RegtestsMode.REPLAY) {
            // REG-15: the real DataSource is swapped for the fake before anything can use it. That is why the service starts with the database down: its pool never opens a connection.
            active.events().emit("REG-15", DataSourcePostProcessor.class, "Real DataSource swapped for the fake",
                    "The connection pool is never started, so the service can boot with the database down.");
            return JdbcReplay.dataSource(active);
        }
        // REG-4: in record the real DataSource is wrapped. The real pool still does the work, and every call passes through the capture proxies.
        active.events().emit("REG-4", DataSourcePostProcessor.class, "DataSource wrapped for capture",
                "Wrapped at the JDBC level, so it makes no difference what sits above it.");
        return JdbcRecording.wrap(real, active);
    }
}
