package com.arthadhruva.riskengine.webhook;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The delivery worker's own small connection pool, authenticated as the restricted
 * {@code arthadhruva_worker} role (V15). Deliberately NOT exposed as a {@code DataSource} bean:
 * Spring Boot backs its own DataSource auto-configuration off if one exists, which would replace
 * the app's tenant-tagged pool.
 */
@Component
class WorkerDb implements DisposableBean {

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    WorkerDb(@Value("${spring.datasource.url}") String url,
             @Value("${webhook.worker.db-user}") String user,
             @Value("${webhook.worker.db-password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setPoolName("webhook-worker");
        config.setInitializationFailTimeout(-1); // don't block app startup if the DB isn't reachable yet
        this.pool = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(pool);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
