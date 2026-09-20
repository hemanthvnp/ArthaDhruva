package com.arthadhruva.riskengine.tenant;

/**
 * Marks an entity as belonging to exactly one {@link Organization}. Deliberately a plain
 * interface rather than a {@code @MappedSuperclass}: different entities need different physical
 * representations of "tenant" (a real {@code @ManyToOne Organization} where the org itself is
 * navigated to, a bare {@code tenantId} column on high-volume log rows that never need the org
 * entity, or tenant folded into a composite {@code @EmbeddedId} for entities that use a natural
 * key like loan id as their primary key) -- a single mapped superclass can't cover all three
 * shapes, but every one of them can still answer "which tenant do I belong to."
 */
public interface TenantAware {
    Long getTenantId();
}
