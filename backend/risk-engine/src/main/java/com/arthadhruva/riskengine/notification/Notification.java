package com.arthadhruva.riskengine.notification;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/** A brand-new table (unlike the tenant-scoping retrofits elsewhere in this codebase, this one
 * is created tenant-scoped from day one -- see V9__create_notification.sql). See {@code
 * security.User}'s class doc for why {@code tenantFilter} is a backstop, not the primary guard. */
@Entity
@Table(name = "notification")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Notification implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "recipient_username", nullable = false)
    private String recipientUsername;

    @Column(nullable = false, length = 500)
    private String message;

    /** A frontend route the notification should link to when clicked, e.g. "/loans/L123". Null
     * for notifications with nothing more specific to point at. */
    @Column
    private String link;

    @Column(nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private NotificationType eventType = NotificationType.DIGEST_SUMMARY;

    /** INSTANT (visible), QUEUED (waiting for the user's digest) or DIGESTED (folded into one). */
    @Column(nullable = false)
    private String status = "INSTANT";

    protected Notification() {
        // required by JPA
    }

    public Notification(Long tenantId, String recipientUsername, NotificationType type, String message, String link,
                        boolean queuedForDigest) {
        this(tenantId, recipientUsername, message, link);
        this.eventType = type;
        this.status = queuedForDigest ? "QUEUED" : "INSTANT";
    }

    public Notification(Long tenantId, String recipientUsername, String message, String link) {
        this.tenantId = tenantId;
        this.recipientUsername = recipientUsername;
        this.message = message;
        this.link = link;
        this.createdAt = Instant.now();
    }

    public NotificationType getEventType() {
        return eventType;
    }

    public String getStatus() {
        return status;
    }

    public void markDigested() {
        this.status = "DIGESTED";
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    public String getRecipientUsername() {
        return recipientUsername;
    }

    public String getMessage() {
        return message;
    }

    public String getLink() {
        return link;
    }

    public boolean isRead() {
        return read;
    }

    public void markRead() {
        this.read = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
