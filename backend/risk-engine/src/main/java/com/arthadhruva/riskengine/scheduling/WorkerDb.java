package com.arthadhruva.riskengine.scheduling;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A small connection pool for background work that looks across organizations, authenticated as the
 * restricted {@code arthadhruva_worker} role: draining the webhook outbox (V15) and claiming or requeueing
 * portfolio runs (V29). The role is granted those tables and nothing else, so the app role never needs
 * cross-tenant access. Deliberately NOT exposed as a {@code DataSource} bean: Spring Boot backs its own
 * DataSource auto-configuration off if one exists, which would replace the app's tenant-tagged pool.
 */
@Component
public class WorkerDb implements DisposableBean {

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    WorkerDb(@Value("${spring.datasource.url}") String url,
             @Value("${webhook.worker.db-user}") String user,
             @Value("${webhook.worker.db-password}") String password,
             @Value("${webhook.worker.concurrency:4}") int deliveries) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        // One per concurrent webhook delivery, and two for the claims and the run supervisor.
        config.setMaximumPoolSize(Math.max(1, deliveries) + 2);
        config.setMinimumIdle(0);
        config.setPoolName("worker");
        config.setInitializationFailTimeout(-1); // don't block app startup if the DB isn't reachable yet
        this.pool = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(pool);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
