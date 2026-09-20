package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Specification pattern: each predicate is a small, independently testable, composable object,
 * and a query is built at runtime by AND-ing whichever ones the caller supplied -- instead of one
 * repository method per filter combination (2^n of them). Tenant scoping is the first predicate
 * of every composed query and cannot be omitted: {@link #forTenant} is the only public entry
 * point that builds a full query, and it always starts from {@link #tenant}.
 */
public final class LoanCaseSpecs {

    private LoanCaseSpecs() {
    }

    public static Specification<LoanCase> tenant(Long tenantId) {
        return (root, query, cb) -> cb.equal(root.get("id").get("tenantId"), tenantId);
    }

    public static Specification<LoanCase> hasStatus(LoanCaseStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<LoanCase> isFlagged(boolean flagged) {
        return (root, query, cb) -> cb.equal(root.get("flagged"), flagged);
    }

    public static Specification<LoanCase> assignedTo(String username) {
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), username);
    }

    /** An empty collection matches nothing (not everything): "state has no loans" must not widen the query. */
    public static Specification<LoanCase> loanIdIn(Collection<String> loanIds) {
        return (root, query, cb) -> loanIds.isEmpty() ? cb.disjunction() : root.get("id").get("loanId").in(loanIds);
    }

    public static Specification<LoanCase> updatedBetween(Instant from, Instant to) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), from));
            if (to != null) p.add(cb.lessThan(root.get("updatedAt"), to));
            return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    public static Specification<LoanCase> forTenant(Long tenantId, LoanCaseFilter f) {
        List<Specification<LoanCase>> parts = new ArrayList<>();
        parts.add(tenant(tenantId));
        if (f.status() != null) parts.add(hasStatus(f.status()));
        if (f.flagged() != null) parts.add(isFlagged(f.flagged()));
        if (f.assignedTo() != null && !f.assignedTo().isBlank()) parts.add(assignedTo(f.assignedTo()));
        if (f.loanIds() != null) parts.add(loanIdIn(f.loanIds()));
        if (f.updatedFrom() != null || f.updatedTo() != null) parts.add(updatedBetween(f.updatedFrom(), f.updatedTo()));
        return Specification.allOf(parts);
    }
}
