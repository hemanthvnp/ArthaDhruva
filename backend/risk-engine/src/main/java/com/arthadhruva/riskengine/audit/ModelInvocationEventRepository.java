package com.arthadhruva.riskengine.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelInvocationEventRepository extends JpaRepository<ModelInvocationEvent, Long> {
    /** Scoped to one tenant -- an admin only audits their own org's model invocations. Rows with
     * a null {@code tenantId} (endpoints excluded from tenant context) never appear in any
     * tenant's view; a platform-wide view across those is a later, separate concern. */
    Page<ModelInvocationEvent> findAllByTenantIdOrderByOccurredAtDesc(Long tenantId, Pageable pageable);
}
