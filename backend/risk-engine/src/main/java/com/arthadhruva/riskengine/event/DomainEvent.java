package com.arthadhruva.riskengine.event;

import java.time.Instant;

/**
 * Base for events published via Spring's {@code ApplicationEventPublisher} (Observer pattern,
 * design decision 1) so that side effects of a state change (notifications, automation rules,
 * webhooks) are independent listeners rather than direct method calls from the module that
 * caused the change. Deliberately a plain POJO, not a Spring {@code ApplicationEvent} subclass --
 * that base class predates Spring's support for listening on arbitrary event types and adds
 * nothing here.
 */
public abstract class DomainEvent {

    private final Long tenantId;
    private final Instant occurredAt;

    protected DomainEvent(Long tenantId) {
        this.tenantId = tenantId;
        this.occurredAt = Instant.now();
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
