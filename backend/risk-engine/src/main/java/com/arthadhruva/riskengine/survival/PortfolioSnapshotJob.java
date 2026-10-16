package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.score.TenantLoanService;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The monthly portfolio risk snapshot: once a month, across all replicas, every organization with a
 * portfolio of its own gets its lifetime ECL, staging and run-off recomputed and stored, which is what
 * builds the trend over time. Organizations still on the shared demo catalog are computed on demand.
 *
 * <p>The job does not project anything itself. It records a deferred run per organization and returns:
 * the runs are then worked like any other (taken by an idle replica, heartbeating, restarted and resumed
 * if their worker dies), and the scheduler thread is free again in milliseconds.
 *
 * <p>The schedule fires daily, once across all replicas, and each pass queues a run only for organizations
 * that have no snapshot yet this month. So a month is not lost to one bad night: if the replica dies
 * part-way through a pass, or an organization's run fails, the next day's pass queues what is still
 * missing, and an organization that has its snapshot is left alone for the rest of the month.
 */
@Component
class PortfolioSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotJob.class);
    private static final List<Scenario> SCENARIOS = List.of(Scenario.BASELINE, Scenario.ADVERSE, Scenario.SEVERELY_ADVERSE);
    static final String REQUESTED_BY = "monthly-snapshot";

    private final PortfolioRiskService service;
    private final TenantLoanService loans;
    private final OrganizationService organizations;
    private final DistributedLock lock;

    PortfolioSnapshotJob(PortfolioRiskService service, TenantLoanService loans, OrganizationService organizations,
                         DistributedLock lock) {
        this.service = service;
        this.loans = loans;
        this.organizations = organizations;
        this.lock = lock;
    }

    @Scheduled(cron = "${risk.portfolio.snapshot-cron:0 30 3 * * *}")
    void run() {
        lock.runOncePerPeriod("portfolio-risk-snapshot", lock.period(DistributedLock.DAILY), () -> {
            LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
            int queued = 0;
            for (Long tenantId : organizations.activeIds()) {
                TenantContext.set(tenantId);
                try {
                    if (loans.hasPortfolio(tenantId) && !service.hasSnapshotSince(tenantId, monthStart)) {
                        service.start(tenantId, REQUESTED_BY, loans.all(tenantId), SCENARIOS, null, true);
                        queued++;
                    }
                } catch (PortfolioRiskService.RunInProgressException e) {
                    // That run stores a snapshot for this month when it finishes.
                    log.info("Tenant {} has a portfolio run in progress; no monthly snapshot run queued", tenantId);
                } catch (Exception e) {
                    log.error("The monthly portfolio snapshot could not be queued for tenant {}; continuing", tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
            if (queued > 0) {
                log.info("Monthly portfolio snapshot: {} run(s) queued", queued);
            }
        });
    }
}
