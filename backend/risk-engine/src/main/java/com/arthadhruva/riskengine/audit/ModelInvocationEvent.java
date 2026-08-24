package com.arthadhruva.riskengine.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One immutable row per call to any model-serving controller endpoint (SR 11-7-style audit
 * trail) -- written by {@link AuditAspect}, never updated or deleted. Endpoints are identified
 * by their join-point signature (e.g. {@code ScoreController.score}) rather than a hardcoded
 * enum, so a new controller is captured automatically without touching this class.
 */
@Entity
@Table(name = "model_invocation_events")
public class ModelInvocationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public ModelInvocationEvent(String endpoint, String requestJson, String responseJson,
                                 boolean success, String errorMessage, Instant occurredAt, long latencyMs) {
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
