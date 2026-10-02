package com.arthadhruva.riskengine.idempotency;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly removal of idempotency records older than 24 hours, for every tenant (each under its own
 * context, so row-level security scopes each delete). */
@Component
class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);
    static final int RETENTION_HOURS = 24;

    private final IdempotencyStore store;
    private final OrganizationService organizations;
    private final DistributedLock lock;

    IdempotencyPurgeJob(IdempotencyStore store, OrganizationService organizations, DistributedLock lock) {
        this.store = store;
        this.organizations = organizations;
        this.lock = lock;
    }

    @Scheduled(cron = "${idempotency.purge-cron:0 17 * * * *}")
    void run() {
        lock.runOncePerPeriod("idempotency-purge", lock.period(DistributedLock.HOURLY), () -> {
            int removed = 0;
            for (Long tenantId : organizations.activeIds()) {
                TenantContext.set(tenantId);
                try {
                    removed += store.purgeOlderThanHours(RETENTION_HOURS);
                } finally {
                    TenantContext.clear();
                }
            }
            log.info("Idempotency purge removed {} expired record(s)", removed);
        });
    }
}
