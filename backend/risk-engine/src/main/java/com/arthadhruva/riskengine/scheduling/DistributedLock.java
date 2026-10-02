package com.arthadhruva.riskengine.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cluster-wide coordination for scheduled jobs.
 *
 * <p>{@link #runExclusively}: a Postgres advisory try-lock -- with N replicas firing the same
 * {@code @Scheduled} method, at most one runs at a time. The lock belongs to the database session, so a
 * JVM that dies mid-job releases it the moment its connection drops (no TTL to guess).
 *
 * <p>A lock alone does not give "once per period": a replica whose clock fires a moment after another
 * finished would take the free lock and run the job again. {@link #runOncePerPeriod} adds a row per
 * (job, period) in {@code job_run}; only the replica that inserts it runs the job. A failed run deletes its
 * row, so a later tick can retry that period.
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    public static final DateTimeFormatter HOURLY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    public static final DateTimeFormatter DAILY = DateTimeFormatter.ISO_LOCAL_DATE;
    public static final DateTimeFormatter MONTHLY = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired   // two constructors: Spring needs to be told which one is its
    public DistributedLock(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DistributedLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The current period key (UTC) for a period format. */
    public String period(DateTimeFormatter format) {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).format(format);
    }

    /** @return true if this instance held the lock and ran the task; false if another one holds it */
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

    /** Runs {@code task} at most once per {@code periodKey} across all replicas. */
    public boolean runOncePerPeriod(String name, String periodKey, Runnable task) {
        AtomicBoolean ran = new AtomicBoolean(false);
        runExclusively(name, () -> {
            if (jdbc.update("INSERT INTO job_run (job_name, period_key) VALUES (?, ?) ON CONFLICT DO NOTHING", name, periodKey) == 0) {
                log.debug("Job '{}' already ran for period {}", name, periodKey);
                return;
            }
            try {
                task.run();
                jdbc.update("UPDATE job_run SET finished_at = now() WHERE job_name = ? AND period_key = ?", name, periodKey);
                ran.set(true);
            } catch (RuntimeException e) {
                jdbc.update("DELETE FROM job_run WHERE job_name = ? AND period_key = ?", name, periodKey);
                throw e;
            }
        });
        return ran.get();
    }
}
