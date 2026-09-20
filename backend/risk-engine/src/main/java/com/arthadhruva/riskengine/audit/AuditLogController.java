package com.arthadhruva.riskengine.audit;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN-only (enforced by SecurityConfig's {@code /admin/**} rule, not here): makes the audit
 * trail every other endpoint already writes to actually reviewable by an authorized person,
 * instead of only queryable by whoever has direct Postgres access. Scoped to the caller's own
 * organization -- an ADMIN audits their own tenant's model invocations, never another tenant's.
 */
@RestController
public class AuditLogController {

    private static final int MAX_LIMIT = 500;

    private final AuditEventWriter auditEventWriter;

    public AuditLogController(AuditEventWriter auditEventWriter) {
        this.auditEventWriter = auditEventWriter;
    }

    @GetMapping("/admin/audit-log")
    public List<ModelInvocationEvent> recent(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return auditEventWriter.recentForTenant(TenantContext.get(), PageRequest.of(0, bounded)).getContent();
    }
}
