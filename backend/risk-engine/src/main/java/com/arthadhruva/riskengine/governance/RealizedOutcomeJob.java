package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The monthly comparison of stored scores with recorded outcomes. It does no arithmetic: for each organization that has
 * recorded outcomes and has no report yet this month it inserts a QUEUED row and hands it to a compute thread
 * ({@link RealizedOutcomeService#request}), then moves on, so the shared scheduler pool is free again in milliseconds.
 *
 * <p>It fires daily, once across all replicas, and an organization that has its report for the month is left alone, so a
 * month is not lost to one bad night; a report whose worker died is closed as stale and the next pass queues a new one.
 */
@Component
class RealizedOutcomeJob {

    private static final Logger log = LoggerFactory.getLogger(RealizedOutcomeJob.class);
    static final String REQUESTED_BY = "monthly-comparison";

    private final RealizedOutcomeService outcomes;
    private final OrganizationService organizations;
    private final DistributedLock lock;

    RealizedOutcomeJob(RealizedOutcomeService outcomes, OrganizationService organizations, DistributedLock lock) {
        this.outcomes = outcomes;
        this.organizations = organizations;
        this.lock = lock;
    }

    @Scheduled(cron = "${governance.realized.cron:0 10 4 * * *}")
    void run() {
        lock.runOncePerPeriod("realized-outcomes-report", lock.period(DistributedLock.DAILY), this::queueAll);
    }

    /** Queues a report for every organization that has outcomes and none this month; returns how many were queued. */
    int queueAll() {
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        int queued = 0;
        for (Long tenantId : organizations.activeIds()) {
            TenantContext.set(tenantId);
            try {
                if (outcomes.outcomeCount(tenantId) > 0 && !outcomes.hasReportSince(tenantId, monthStart)) {
                    outcomes.request(tenantId, REQUESTED_BY);
                    queued++;
                }
            } catch (Exception e) {
                log.error("The monthly comparison of stored scores with outcomes could not be queued for tenant {}; continuing", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
        if (queued > 0) {
            log.info("Monthly score-versus-outcome comparison: {} report(s) queued", queued);
        }
        return queued;
    }
}
