package com.arthadhruva.riskengine.retention;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V31 on a database that already has an audit trail, as production does: every row survives with its id,
 * each lands in its own month's partition, and new rows continue the old numbering. Run on a database of
 * its own, migrated to V29 first and filled, so the shared test database is not touched.
 */
class AuditPartitionMigrationTest extends AbstractIntegrationTest {

    @Value("${spring.datasource.url}") String url;
    @Value("${spring.flyway.user}") String ownerUser;
    @Value("${spring.flyway.password}") String ownerPassword;

    private Flyway flyway(String jdbcUrl, String target) {
        return Flyway.configure()
                .dataSource(jdbcUrl, ownerUser, ownerPassword)
                .placeholders(Map.of("appDbPassword", "arthadhruva_app", "workerDbPassword", "arthadhruva_worker"))
                .target(target)
                .load();
    }

    @Test
    void existingRowsMoveIntoTheirMonthsAndKeepTheirIds() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        String db = "v31_" + System.nanoTime();
        admin.execute("CREATE DATABASE " + db);
        String dbUrl = url.replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        try {
            flyway(dbUrl, "29").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(dbUrl, ownerUser, ownerPassword));
            String insert = "INSERT INTO model_invocation_events (endpoint, latency_ms, occurred_at, success, tenant_id, actor) "
                    + "VALUES ('Before', 3, %s, true, NULL, 'ana')";
            jdbc.update(insert.formatted("now() - interval '13 months'"));
            jdbc.update(insert.formatted("now() - interval '1 month'"));
            jdbc.update(insert.formatted("now()"));
            List<Long> idsBefore = jdbc.queryForList("SELECT id FROM model_invocation_events ORDER BY id", Long.class);

            flyway(dbUrl, "31").migrate();

            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, tableoid::regclass::text AS part, "
                    + "'model_invocation_events_' || to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY_MM') AS month, actor "
                    + "FROM model_invocation_events ORDER BY id");
            assertEquals(idsBefore, rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList());
            for (Map<String, Object> row : rows) {
                assertEquals(row.get("month"), row.get("part"), "each row in its own month");
                assertEquals("ana", row.get("actor"));
            }
            long next = jdbc.queryForObject("INSERT INTO model_invocation_events (endpoint, latency_ms, occurred_at, success) "
                    + "VALUES ('After', 1, now(), true) RETURNING id", Long.class);
            assertTrue(next > idsBefore.get(idsBefore.size() - 1), "new ids continue after the old ones");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM model_invocation_events_default", Integer.class));
        } finally {
            admin.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + db + "' AND pid <> pg_backend_pid()");
            admin.execute("DROP DATABASE IF EXISTS " + db);
        }
    }
}
