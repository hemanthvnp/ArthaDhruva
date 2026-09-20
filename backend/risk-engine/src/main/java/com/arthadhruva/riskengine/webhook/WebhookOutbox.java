package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;
import java.util.UUID;

/** One pending delivery of one event to one subscription. Written in the same transaction as the
 * state change (see {@link WebhookOutboxWriter}); the delivery worker updates status via JDBC. */
@Entity
@Table(name = "webhook_outbox")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class WebhookOutbox implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookOutbox() {
    }

    public WebhookOutbox(UUID eventId, Long tenantId, Long subscriptionId, WebhookEventType type, String payload) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.subscriptionId = subscriptionId;
        this.eventType = type.name();
        this.payload = payload;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    @Override public Long getTenantId() { return tenantId; }
    public Long getSubscriptionId() { return subscriptionId; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
}
