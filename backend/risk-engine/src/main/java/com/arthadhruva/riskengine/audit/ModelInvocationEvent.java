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
 * One append-only row per audited API call (SR 11-7-style audit trail): who ({@code actor}), what
 * ({@code endpoint}, method, path, redacted request and -- for writes -- redacted response), which model
 * version answered, the outcome and the latency. The application's database role holds INSERT but no
 * UPDATE or DELETE on this table (V26), so a row, once written, cannot be altered by the application.
 *
 * <p>{@code tenantId} is nullable: a few audited endpoints run before a tenant is known.
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

    @Column(name = "actor")
    private String actor;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "path")
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "model_version")
    private String modelVersion;

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

    /** Adds the request context: who, how, with what outcome and which model version. */
    public ModelInvocationEvent withContext(String actor, String httpMethod, String path, Integer statusCode,
                                            String clientIp, String modelVersion) {
        this.actor = truncate(actor, 255);
        this.httpMethod = truncate(httpMethod, 10);
        this.path = truncate(path, 500);
        this.statusCode = statusCode;
        this.clientIp = truncate(clientIp, 64);
        this.modelVersion = truncate(modelVersion, 64);
        return this;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
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

    public String getActor() {
        return actor;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getModelVersion() {
        return modelVersion;
    }
}
