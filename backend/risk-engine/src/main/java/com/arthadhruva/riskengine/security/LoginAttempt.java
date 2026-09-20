package com.arthadhruva.riskengine.security;

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
 * A credential-free record of every login attempt -- username and outcome only, never the
 * password. Written directly by AuthController (not via AuditAspect, which stays excluded from
 * AuthController entirely -- see that class's doc). Logging attempts against usernames that
 * don't exist is deliberate: that's exactly the signal an account-enumeration or credential-
 * stuffing sweep would produce.
 *
 * {@code tenantId} is nullable -- unlike every other tenant-scoped entity, a login attempt
 * against an unresolvable org slug still can't be assigned a real tenant, and that's exactly the
 * enumeration signal worth keeping (same reasoning already applied to nonexistent usernames), so
 * this is the one deliberate exception to "tenant_id is NOT NULL." A null-tenant row is invisible
 * whenever {@code tenantFilter} is active (SQL {@code NULL = :tenantId} is never true) -- the same
 * behavior {@code LoginAttemptRepository}'s own tenant-scoped query already has.
 *
 * <p>See {@code User}'s class doc for why this filter is a backstop, not the primary guard.
 */
@Entity
@Table(name = "login_attempt")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoginAttempt implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoginAttempt() {
        // required by JPA
    }

    public LoginAttempt(Long tenantId, String username, boolean success, Instant occurredAt) {
        this.tenantId = tenantId;
        this.username = username;
        this.success = success;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isSuccess() {
        return success;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
