package com.arthadhruva.riskengine.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A customer organization -- the tenant boundary every customer-generated row in this app will be
 * scoped to (see {@link TenantAware}). {@code slug} is the human-facing identifier used at login
 * (see AuthController); {@code id} is the internal value every tenant-scoped foreign key/embedded
 * key actually stores -- never expose the raw numeric id in a URL if this ever goes
 * public/multi-region.
 */
@Entity
@Table(name = "organization")
public class Organization {

    /** Seeded by {@code V2__create_organization_table.sql} and assigned to every row that
     * existed before multi-tenancy was introduced (see that migration's backfill) -- also the
     * tenant {@link com.arthadhruva.riskengine.security.AdminBootstrap} uses for a brand-new,
     * still-empty database. */
    public static final String LEGACY_SLUG = "legacy";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Column default (STARTER) applies on insert; changed later through {@link #setPlanId}. */
    @Column(name = "plan_id", insertable = false)
    private Integer planId;

    @Column(nullable = false)
    private boolean sandbox = false;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "billing_customer_id")
    private String billingCustomerId;

    protected Organization() {
        // required by JPA
    }

    public Organization(String slug, String name) {
        this.slug = slug;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getPlanId() { return planId; }
    public void setPlanId(Integer planId) { this.planId = planId; }
    public boolean isSandbox() { return sandbox; }
    public void setSandbox(boolean sandbox) { this.sandbox = sandbox; }
    public Instant getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(Instant trialEndsAt) { this.trialEndsAt = trialEndsAt; }
    public String getBillingCustomerId() { return billingCustomerId; }
    public void setBillingCustomerId(String id) { this.billingCustomerId = id; }
}
