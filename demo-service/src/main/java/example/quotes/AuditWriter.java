package example.quotes;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

/** Writes the audit row on another thread, after the caller may already have its answer. */
@Component
class AuditWriter {

    private final JdbcTemplate jdbc;

    AuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void quoteIssued(String quoteId, BigDecimal premium) {
        // REG-6: plain JDBC. Ordinary SQL through JdbcTemplate, no MyBatis and no procedure, and still the same DataSource underneath.
        jdbc.update("INSERT INTO audit_log (quote_id, action, detail, created_at) VALUES (?, ?, ?, ?)",
                quoteId, "QUOTE_ISSUED", "Premium " + premium, Timestamp.from(Instant.now()));
    }
}
