package com.arthadhruva.riskengine.notification;

import com.arthadhruva.riskengine.scheduling.DistributedLock;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Folds each user's queued (digest-mode) notifications into one summary. Runs on exactly one
 * replica per tick ({@link DistributedLock}) and visits tenants one at a time under that tenant's
 * context, so row-level security applies to every query it makes. */
@Component
class NotificationDigestJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDigestJob.class);

    private final NotificationService notifications;
    private final OrganizationService organizations;
    private final DistributedLock lock;

    NotificationDigestJob(NotificationService notifications, OrganizationService organizations, DistributedLock lock) {
        this.notifications = notifications;
        this.organizations = organizations;
        this.lock = lock;
    }

    @Scheduled(cron = "${notifications.digest-cron:0 0 8 * * *}")
    void run() {
        lock.runExclusively("notification-digest", () -> {
            int total = 0;
            for (Long tenantId : organizations.activeIds()) {
                TenantContext.set(tenantId);
                try {
                    total += notifications.deliverDigests(tenantId);
                } catch (Exception e) {
                    log.error("Digest failed for tenant {}; continuing with the others", tenantId, e);
                } finally {
                    TenantContext.clear();
                }
            }
            log.info("Notification digest run complete: {} digest(s) produced", total);
        });
    }
}
