package com.arthadhruva.riskengine.retention;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the tables that only grow from growing without end, once a day on one replica.
 *
 * <ul>
 *   <li><b>Audit trail</b> ({@code model_invocation_events}, partitioned by month, V31): the months ahead
 *       get their partitions in advance, and months older than {@code retention.audit-months} (seven
 *       years by default, never under one in the database) are dropped whole.</li>
 *   <li><b>Everything else that only grows</b> (delivered and failed webhooks, notifications, login
 *       attempts, the job ledger): rows past their retention are deleted in batches, each batch its own
 *       short statement, until a batch finds nothing or the run reaches its batch limit; whatever is left
 *       goes the next day.</li>
 * </ul>
 *
 * The work itself is done by database functions owned by the schema owner (the app role may neither
 * delete from the audit trail nor create tables), each with a floor under its retention period.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final DistributedLock lock;
    private final Counter rowsDeleted;
    private final Counter partitionsDropped;

    private final int auditMonths;
    private final int monthsAhead;
    private final int batchSize;
    private final int maxBatches;
    private final int deliveredWebhookDays;
    private final int failedWebhookDays;
    private final int readNotificationDays;
    private final int notificationDays;
    private final int loginAttemptDays;
    private final int jobRunDays;

    public RetentionJob(JdbcTemplate jdbc, DistributedLock lock, MeterRegistry meters,
                        @Value("${retention.audit-months:84}") int auditMonths,
                        @Value("${retention.audit-months-ahead:3}") int monthsAhead,
                        @Value("${retention.batch-size:5000}") int batchSize,
                        @Value("${retention.max-batches:200}") int maxBatches,
                        @Value("${retention.webhook-delivered-days:30}") int deliveredWebhookDays,
                        @Value("${retention.webhook-failed-days:90}") int failedWebhookDays,
                        @Value("${retention.notification-read-days:90}") int readNotificationDays,
                        @Value("${retention.notification-days:365}") int notificationDays,
                        @Value("${retention.login-attempt-days:365}") int loginAttemptDays,
                        @Value("${retention.job-run-days:30}") int jobRunDays) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.rowsDeleted = Counter.builder("retention.rows.deleted").register(meters);
        this.partitionsDropped = Counter.builder("retention.partitions.dropped").register(meters);
        this.auditMonths = auditMonths;
        this.monthsAhead = monthsAhead;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.deliveredWebhookDays = deliveredWebhookDays;
        this.failedWebhookDays = failedWebhookDays;
        this.readNotificationDays = readNotificationDays;
        this.notificationDays = notificationDays;
        this.loginAttemptDays = loginAttemptDays;
        this.jobRunDays = jobRunDays;
    }

    /** At start-up too, so a server that was off over a month boundary has its partitions before it writes. */
    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        lock.runExclusively("audit-partitions", this::ensurePartitions);
    }

    @Scheduled(cron = "${retention.cron:0 40 2 * * *}")
    void daily() {
        lock.runOncePerPeriod("retention", lock.period(DistributedLock.DAILY), this::run);
    }

    void run() {
        ensurePartitions();
        Integer dropped = jdbc.queryForObject("SELECT audit_drop_partitions(?)", Integer.class, auditMonths);
        partitionsDropped.increment(dropped == null ? 0 : dropped);
        long deleted = purge();
        log.info("Retention: dropped {} audit partition(s) older than {} months, deleted {} expired row(s)",
                dropped, auditMonths, deleted);
    }

    int ensurePartitions() {
        Integer created = jdbc.queryForObject("SELECT audit_ensure_partitions(?)", Integer.class, monthsAhead);
        if (created != null && created > 0) {
            log.info("Created {} audit partition(s)", created);
        }
        return created == null ? 0 : created;
    }

    long purge() {
        long total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            Integer n = jdbc.queryForObject("SELECT purge_expired_rows(?, ?, ?, ?, ?, ?, ?)", Integer.class, batchSize,
                    deliveredWebhookDays, failedWebhookDays, readNotificationDays, notificationDays, loginAttemptDays, jobRunDays);
            int deleted = n == null ? 0 : n;
            total += deleted;
            rowsDeleted.increment(deleted);
            if (deleted == 0) {
                return total;
            }
        }
        log.warn("Retention stopped after {} batches with rows still to delete; the rest goes tomorrow", maxBatches);
        return total;
    }
}
