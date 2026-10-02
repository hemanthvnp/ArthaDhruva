package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.score.TenantLoanService;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The monthly portfolio risk snapshot: once a month, across all replicas, every organization with a
 * portfolio of its own gets its lifetime ECL, staging and run-off recomputed and stored, which is what
 * builds the trend over time. Organizations still on the shared demo catalog are computed on demand.
 * One organization at a time, under its tenant context, so row-level security applies; one failure does
 * not stop the rest.
 */
@Component
class PortfolioSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotJob.class);
    private static final List<Scenario> SCENARIOS = List.of(Scenario.BASELINE, Scenario.ADVERSE, Scenario.SEVERELY_ADVERSE);

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

    @Scheduled(cron = "${risk.portfolio.snapshot-cron:0 30 3 1 * *}")
    void run() {
        lock.runOncePerPeriod("portfolio-risk-snapshot", lock.period(DistributedLock.MONTHLY), () -> {
            for (Long tenantId : organizations.activeIds()) {
                TenantContext.set(tenantId);
                try {
                    if (loans.hasPortfolio(tenantId)) {
                        service.runNow(tenantId, loans.all(tenantId), SCENARIOS);
                    }
                } catch (Exception e) {
                    log.error("Portfolio risk snapshot failed for tenant {}; continuing", tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
        });
    }
}
