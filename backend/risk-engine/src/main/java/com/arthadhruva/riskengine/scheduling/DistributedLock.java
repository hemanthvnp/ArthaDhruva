package com.arthadhruva.riskengine.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide mutual exclusion for scheduled jobs, using Postgres advisory locks: with N
 * replicas each firing the same {@code @Scheduled} method, exactly one runs it per tick.
 *
 * <p>Why an advisory lock rather than a Redis {@code SET NX EX} lock: the lock belongs to the
 * database <em>session</em>, so if the JVM dies mid-job Postgres releases it the instant the
 * connection drops, with no TTL to guess (too short and a slow job runs twice; too long and a
 * crashed job blocks the next run). It is a try-lock: a replica that loses the race skips the tick
 * instead of queueing behind the winner.
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private final JdbcTemplate jdbc;

    public DistributedLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this instance held the lock and ran the task; false if another one did */
    public boolean runExclusively(String name, Runnable task) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            boolean acquired;
            try (var ps = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
                ps.setString(1, name);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
            }
            if (!acquired) {
                log.debug("Job '{}' is running on another instance; skipping this tick", name);
                return false;
            }
            try {
                task.run();
                return true;
            } finally {
                try (var ps = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
                    ps.setString(1, name);
                    ps.execute();
                }
            }
        }));
    }
}
