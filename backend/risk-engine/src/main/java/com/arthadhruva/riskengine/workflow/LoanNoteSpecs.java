package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Same pattern as {@link LoanCaseSpecs}, for notes; tenant predicate always first. */
public final class LoanNoteSpecs {

    private LoanNoteSpecs() {
    }

    public static Specification<LoanNote> tenant(Long tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    /** Case-insensitive substring; LIKE metacharacters in the user's text are escaped so a search
     * for "50%" means the literal string, not a wildcard. */
    public static Specification<LoanNote> textContains(String text) {
        String pattern = "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("text")), pattern, '\\');
    }

    public static Specification<LoanNote> byAuthor(String author) {
        return (root, query, cb) -> cb.equal(root.get("author"), author);
    }

    public static Specification<LoanNote> forLoan(String loanId) {
        return (root, query, cb) -> cb.equal(root.get("loanId"), loanId);
    }

    public static Specification<LoanNote> createdBetween(Instant from, Instant to) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) p.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    public static Specification<LoanNote> forTenant(Long tenantId, String text, String author, String loanId,
                                                    Instant from, Instant to) {
        List<Specification<LoanNote>> parts = new ArrayList<>();
        parts.add(tenant(tenantId));
        if (text != null && !text.isBlank()) parts.add(textContains(text));
        if (author != null && !author.isBlank()) parts.add(byAuthor(author));
        if (loanId != null && !loanId.isBlank()) parts.add(forLoan(loanId));
        if (from != null || to != null) parts.add(createdBetween(from, to));
        return Specification.allOf(parts);
    }
}
