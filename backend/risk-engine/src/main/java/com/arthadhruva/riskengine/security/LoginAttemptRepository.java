package com.arthadhruva.riskengine.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {
    /** Scoped to one tenant -- rows with a null {@code tenantId} (an attempt against an
     * unresolvable org slug) never match a real tenant's admin view; a platform-wide view across
     * those is a later, separate concern. */
    Page<LoginAttempt> findAllByTenantIdOrderByOccurredAtDesc(Long tenantId, Pageable pageable);
}
