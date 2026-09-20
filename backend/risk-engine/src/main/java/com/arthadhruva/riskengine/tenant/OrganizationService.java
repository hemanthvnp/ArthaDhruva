package com.arthadhruva.riskengine.tenant;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The {@code tenant} module's public façade -- {@link OrganizationRepository} is private to this
 * package; every other module resolves/references an {@link Organization} through here instead
 * of injecting the repository directly.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    /** The login-time lookup -- an inactive (suspended) org must resolve the same as a
     * nonexistent one to the caller (see AuthController), never revealing which case it is. */
    public Optional<Organization> resolveActiveBySlug(String slug) {
        return organizationRepository.findBySlugAndActiveTrue(slug);
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, long[]> activeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether an organization may currently authenticate. Cached briefly (30s) because every
     * authenticated request asks; a suspension therefore takes effect within that window. */
    public boolean isActive(Long organizationId) {
        long now = System.currentTimeMillis();
        long[] hit = activeCache.get(organizationId);
        if (hit != null && hit[1] > now) {
            return hit[0] == 1;
        }
        boolean active = organizationRepository.findById(organizationId).map(Organization::isActive).orElse(false);
        activeCache.put(organizationId, new long[]{active ? 1 : 0, now + 30_000});
        return active;
    }

    public boolean isSandbox(Long organizationId) {
        return organizationRepository.findById(organizationId).map(Organization::isSandbox).orElse(false);
    }

    public boolean slugTaken(String slug) {
        return organizationRepository.existsBySlug(slug);
    }

    public Organization create(String slug, String name, boolean sandbox, Integer planId, java.time.Instant trialEndsAt) {
        Organization org = new Organization(slug, name);
        org.setSandbox(sandbox);
        org.setTrialEndsAt(trialEndsAt);
        org = organizationRepository.save(org);
        if (planId != null) {
            org.setPlanId(planId);
            org = organizationRepository.save(org);
        }
        return org;
    }

    public java.util.List<Organization> listAll() {
        return organizationRepository.findAll();
    }

    public boolean setActive(Long organizationId, boolean active) {
        return organizationRepository.findById(organizationId).map(o -> {
            o.setActive(active);
            organizationRepository.save(o);
            activeCache.remove(organizationId);
            return true;
        }).orElse(false);
    }

    public void setBillingCustomerId(Long organizationId, String customerId) {
        organizationRepository.findById(organizationId).ifPresent(o -> {
            o.setBillingCustomerId(customerId);
            organizationRepository.save(o);
        });
    }

    /** Ids of every active organization -- for background jobs that must visit each tenant in turn
     * (the organization table itself is not tenant-scoped). */
    public java.util.List<Long> activeIds() {
        return organizationRepository.findAll().stream().filter(Organization::isActive).map(Organization::getId).toList();
    }

    /** A lazy reference (no SELECT) for attaching an existing organization to a new/updated
     * entity -- the caller already knows the id is valid (it came from {@link TenantContext},
     * populated from an already-authenticated request), so a full fetch would be wasted work. */
    public Organization getReference(Long organizationId) {
        return organizationRepository.getReferenceById(organizationId);
    }

    /** Used only by the bootstrap-admin path on a brand-new database: on any real deployment
     * this row already exists (seeded by {@code V2__create_organization_table.sql}). */
    public Organization findOrCreate(String slug, String name) {
        return organizationRepository.findBySlugAndActiveTrue(slug)
                .orElseGet(() -> organizationRepository.save(new Organization(slug, name)));
    }
}
