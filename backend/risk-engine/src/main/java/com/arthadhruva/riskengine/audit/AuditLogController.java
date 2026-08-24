package com.arthadhruva.riskengine.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN-only (enforced by SecurityConfig's {@code /admin/**} rule, not here): makes the audit
 * trail every other endpoint already writes to actually reviewable by an authorized person,
 * instead of only queryable by whoever has direct Postgres access.
 */
@RestController
public class AuditLogController {

    private static final int MAX_LIMIT = 500;

    private final ModelInvocationEventRepository repository;

    public AuditLogController(ModelInvocationEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/admin/audit-log")
    public List<ModelInvocationEvent> recent(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository
                .findAll(PageRequest.of(0, bounded, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .getContent();
    }
}
