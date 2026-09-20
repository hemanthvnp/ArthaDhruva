package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "webhook_subscription")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class WebhookSubscription implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String url;

    /** Comma-separated {@link WebhookEventType} names. */
    @Column(name = "event_types", nullable = false)
    private String eventTypes;

    @Column(nullable = false)
    private String secret;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WebhookSubscription() {
    }

    public WebhookSubscription(Long tenantId, String url, List<WebhookEventType> types, String secret) {
        this.tenantId = tenantId;
        this.url = url;
        this.eventTypes = String.join(",", types.stream().map(Enum::name).toList());
        this.secret = secret;
    }

    public Long getId() { return id; }
    @Override public Long getTenantId() { return tenantId; }
    public String getUrl() { return url; }
    public String getSecret() { return secret; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public List<WebhookEventType> getEventTypes() {
        return Arrays.stream(eventTypes.split(",")).map(WebhookEventType::valueOf).toList();
    }

    public boolean subscribesTo(WebhookEventType type) {
        return enabled && getEventTypes().contains(type);
    }
}
