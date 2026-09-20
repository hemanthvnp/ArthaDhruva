package com.arthadhruva.riskengine.notification;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "notification_preference")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class NotificationPreference implements TenantAware {

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "tenant_id") private Long tenantId;
        @Column(name = "username") private String username;
        @Enumerated(EnumType.STRING) @Column(name = "event_type") private NotificationType eventType;

        protected Key() { }
        public Key(Long tenantId, String username, NotificationType eventType) {
            this.tenantId = tenantId; this.username = username; this.eventType = eventType;
        }
        public Long getTenantId() { return tenantId; }
        public String getUsername() { return username; }
        public NotificationType getEventType() { return eventType; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId) && Objects.equals(username, k.username) && eventType == k.eventType;
        }
        @Override public int hashCode() { return Objects.hash(tenantId, username, eventType); }
    }

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryMode mode;

    protected NotificationPreference() { }

    public NotificationPreference(Long tenantId, String username, NotificationType type, DeliveryMode mode) {
        this.id = new Key(tenantId, username, type);
        this.mode = mode;
    }

    @Override public Long getTenantId() { return id.getTenantId(); }
    public NotificationType getType() { return id.getEventType(); }
    public DeliveryMode getMode() { return mode; }
    public void setMode(DeliveryMode mode) { this.mode = mode; }
}
