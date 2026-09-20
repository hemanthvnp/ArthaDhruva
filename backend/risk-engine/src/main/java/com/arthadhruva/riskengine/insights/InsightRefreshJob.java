package com.arthadhruva.riskengine.insights;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly recompute of every tenant's insights, on exactly one replica (advisory lock), one
 * tenant at a time under that tenant's context so row-level security applies. */
@Component
class InsightRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(InsightRefreshJob.class);

    private final InsightService insights;
    private final OrganizationService organizations;
    private final DistributedLock lock;

    InsightRefreshJob(InsightService insights, OrganizationService organizations, DistributedLock lock) {
        this.insights = insights;
        this.organizations = organizations;
        this.lock = lock;
    }

    @Scheduled(cron = "${insights.refresh-cron:0 0 2 * * *}")
    void run() {
        lock.runExclusively("insight-refresh", () -> {
            for (Long tenantId : organizations.activeIds()) {
                TenantContext.set(tenantId);
                try {
                    insights.refresh(tenantId);
                } catch (Exception e) {
                    log.error("Insight refresh failed for tenant {}; continuing", tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
        });
    }
}
