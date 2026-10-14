package com.arthadhruva.riskengine.scheduling;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Leader election among the replicas, by a lease in Postgres ({@code leader_lease}, V28).
 *
 * <p>{@link DistributedLock} answers "may this job run now"; this answers "which replica is in charge",
 * for work that should be driven from one place continuously (supervising runs) rather than raced for on
 * every tick. One statement both takes a free or expired lease and renews a held one, so there is no
 * read-then-write window in which two replicas could both win.
 *
 * <p><b>Safety.</b> A replica believes it leads only until a deadline it computes on its own monotonic
 * clock from the moment <em>before</em> it asked the database, less a margin; the database's expiry is
 * counted from a moment after that. The local belief therefore always ends before another replica can
 * take the lease, whatever the clocks say, and a replica that cannot reach the database simply stops
 * leading when its deadline passes. {@link #fencingToken()} grows on every change of holder, for work that
 * must also be safe against a leader that paused past its deadline without noticing.
 *
 * <p>Renewal runs on its own thread, not the shared scheduler: a long scheduled job must not be able to
 * delay it into losing the lease.
 */
@Component
public class LeaderElection {

    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);
    private static final String SUPERVISOR = "supervisor";

    private final JdbcTemplate jdbc;
    private final String role;
    private final String holder;
    private final Duration lease;
    private final Duration margin;
    private final ScheduledExecutorService renewer;

    private volatile long leaderUntilNanos = System.nanoTime() - 1;
    private volatile long fencingToken;

    @Autowired
    public LeaderElection(JdbcTemplate jdbc, MeterRegistry meters,
                          @Value("${leader.lease-seconds:15}") long leaseSeconds,
                          @Value("${leader.renew-ms:5000}") long renewMillis) {
        this(jdbc, SUPERVISOR, UUID.randomUUID().toString(), Duration.ofSeconds(leaseSeconds), renewMillis);
        Gauge.builder("leader.is.leader", this, e -> e.isLeader() ? 1 : 0)
                .description("1 on the replica that currently holds the supervisor lease").register(meters);
    }

    /**
     * @param role        the lease contended for
     * @param renewMillis how often to campaign; 0 leaves it to the caller (tests)
     */
    LeaderElection(JdbcTemplate jdbc, String role, String holder, Duration lease, long renewMillis) {
        this.jdbc = jdbc;
        this.role = role;
        this.holder = holder;
        this.lease = lease;
        this.margin = lease.dividedBy(5);
        if (renewMillis > 0) {
            this.renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "leader-election");
                thread.setDaemon(true);
                return thread;
            });
            this.renewer.scheduleWithFixedDelay(this::campaign, 0, renewMillis, TimeUnit.MILLISECONDS);
        } else {
            this.renewer = null;
        }
    }

    /** Whether this replica holds the lease right now, by its own clock. */
    public boolean isLeader() {
        return leaderUntilNanos - System.nanoTime() > 0;
    }

    /** The term this replica last won; meaningful while {@link #isLeader()}. */
    public long fencingToken() {
        return fencingToken;
    }

    /** Takes the lease if it is free or expired, renews it if held; one statement, so atomic. */
    void campaign() {
        long asked = System.nanoTime();
        try {
            List<Long> token = jdbc.query("""
                    INSERT INTO leader_lease (name, holder, fencing_token, expires_at)
                    VALUES (?, ?, 1, now() + make_interval(secs => ?))
                    ON CONFLICT (name) DO UPDATE
                       SET holder = EXCLUDED.holder,
                           fencing_token = leader_lease.fencing_token
                                           + CASE WHEN leader_lease.holder = EXCLUDED.holder THEN 0 ELSE 1 END,
                           expires_at = EXCLUDED.expires_at
                     WHERE leader_lease.holder = EXCLUDED.holder OR leader_lease.expires_at < now()
                    RETURNING fencing_token
                    """, (rs, i) -> rs.getLong(1), role, holder, lease.toMillis() / 1000.0);
            if (token.isEmpty()) {
                leaderUntilNanos = asked - 1;   // another replica holds an unexpired lease
                return;
            }
            if (!isLeader()) {
                log.info("This replica now leads (term {})", token.get(0));
            }
            fencingToken = token.get(0);
            leaderUntilNanos = asked + lease.minus(margin).toNanos();
        } catch (RuntimeException e) {
            // Not extended: the lease lapses by itself and another replica takes over.
            log.warn("Leader lease could not be renewed: {}", e.toString());
        }
    }

    /** Gives the lease up at once (a deploy), so the next leader does not wait out the expiry. */
    @PreDestroy
    void resign() {
        if (renewer != null) {
            renewer.shutdownNow();
        }
        boolean led = isLeader();
        leaderUntilNanos = System.nanoTime() - 1;
        if (led) {
            try {
                jdbc.update("UPDATE leader_lease SET expires_at = now() WHERE name = ? AND holder = ?", role, holder);
            } catch (RuntimeException e) {
                log.debug("Could not release the leader lease; it will expire", e);
            }
        }
    }
}
