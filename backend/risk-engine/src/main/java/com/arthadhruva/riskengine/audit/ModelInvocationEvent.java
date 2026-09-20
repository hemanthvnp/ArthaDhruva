package com.arthadhruva.riskengine.audit;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/**
 * One immutable row per call to any model-serving controller endpoint (SR 11-7-style audit
 * trail) -- written by {@link AuditAspect}, never updated or deleted. Endpoints are identified
 * by their join-point signature (e.g. {@code ScoreController.score}) rather than a hardcoded
 * enum, so a new controller is captured automatically without touching this class.
 *
 * {@code tenantId} is nullable: a handful of endpoints this aspect wraps can run without a
 * resolved tenant in context (same reasoning as {@code LoginAttempt.tenantId}). See {@code
 * security.User}'s class doc for why {@code tenantFilter} is a backstop, not the primary guard.
 */
@Entity
@Table(name = "model_invocation_events")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ModelInvocationEvent implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "request_json", columnDefinition = "text")
    private String requestJson;

    @Column(name = "response_json", columnDefinition = "text")
    private String responseJson;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    protected ModelInvocationEvent() {
        // required by JPA
    }

    public ModelInvocationEvent(Long tenantId, String endpoint, String requestJson, String responseJson,
                                 boolean success, String errorMessage, Instant occurredAt, long latencyMs) {
        this.tenantId = tenantId;
        this.endpoint = endpoint;
        this.requestJson = requestJson;
        this.responseJson = responseJson;
        this.success = success;
        this.errorMessage = errorMessage;
        this.occurredAt = occurredAt;
        this.latencyMs = latencyMs;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public long getLatencyMs() {
        return latencyMs;
    }
}
