package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.event.LoanCaseAssignedEvent;
import com.arthadhruva.riskengine.event.LoanCaseNoteAddedEvent;
import com.arthadhruva.riskengine.event.LoanCaseUpdatedEvent;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.search.PageResult;
import com.arthadhruva.riskengine.security.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The {@code workflow} module's facade for cases and notes.
 *
 * <p>Every change goes through {@link #update}, one transaction that:
 * <ol>
 *   <li>reads the current case and, when the caller says which version its change was based on,
 *       refuses the change if the case has moved on (409 with the current state -- no lost updates);</li>
 *   <li>applies the change to the state as it is <em>now</em> (bulk and automation changes are
 *       functions of the current state, never a stale snapshot);</li>
 *   <li>enforces the workflow rules: legal transitions, a written reason where one is required, the
 *       four-eyes rule (whoever escalated a case cannot also clear it), and assignment only to active
 *       staff of the same organization;</li>
 *   <li>writes an immutable history entry and publishes the domain events, so webhook outbox rows
 *       and the case row commit or roll back together.</li>
 * </ol>
 */
@Service
public class LoanCaseService {

    private static final int MAX_LIMIT = 500;
    private static final int MAX_NOTES_SHOWN = 200;
    public static final int MAX_BULK = 100;

    private final LoanCaseRepository loanCaseRepository;
    private final LoanNoteRepository loanNoteRepository;
    private final LoanCaseEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LoanCatalogService loanCatalogService;
    private final UserService userService;
    private final TransactionTemplate transactionTemplate;

    public LoanCaseService(LoanCaseRepository loanCaseRepository, LoanNoteRepository loanNoteRepository,
                           LoanCaseEventRepository eventRepository, ApplicationEventPublisher eventPublisher,
                           LoanCatalogService loanCatalogService, UserService userService,
                           TransactionTemplate transactionTemplate) {
        this.loanCaseRepository = loanCaseRepository;
        this.loanNoteRepository = loanNoteRepository;
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
        this.loanCatalogService = loanCatalogService;
        this.userService = userService;
        this.transactionTemplate = transactionTemplate;
    }

    /** The case for a loan, or a fresh NEW/unassigned/unflagged one (not persisted) if none exists yet. */
    public LoanCase getOrCreate(Long tenantId, String loanId) {
        LoanCaseId id = new LoanCaseId(tenantId, loanId);
        return loanCaseRepository.findById(id).orElseGet(() -> new LoanCase(id));
    }

    /**
     * Applies {@code change} to the case's current state in one transaction.
     *
     * @param expectedVersion the version the caller's change was based on, or null to apply to whatever
     *                        the current state is (bulk operations, automation)
     * @throws CaseRuleException 409 on a stale version, 422 when a workflow rule is broken
     */
    public LoanCase update(Long tenantId, String loanId, String actor, Long expectedVersion, String reason,
                           UnaryOperator<LoanCase.State> change) {
        return transactionTemplate.execute(tx -> {
            LoanCaseId id = new LoanCaseId(tenantId, loanId);
            var existing = loanCaseRepository.findById(id);
            LoanCase loanCase = existing.orElseGet(() -> new LoanCase(id));
            if (expectedVersion != null && expectedVersion != loanCase.getVersion()) {
                throw CaseRuleException.conflict(loanCase);
            }
            LoanCase.State before = loanCase.state();
            LoanCase.State after = change.apply(before);
            validate(tenantId, actor, loanCase, before, after, reason);
            if (after.equals(before) && existing.isPresent()) {
                return loanCase;
            }
            loanCase.apply(after, actor);
            LoanCase saved = loanCaseRepository.save(loanCase);
            eventRepository.save(new LoanCaseEvent(tenantId, loanId, actor, before, after, blankToNull(reason), existing.isPresent()));

            eventPublisher.publishEvent(new LoanCaseUpdatedEvent(tenantId, loanId, after.status(), after.assignedTo(),
                    after.flagged(), before.flagged()));
            if (after.assignedTo() != null && !after.assignedTo().equals(before.assignedTo())) {
                eventPublisher.publishEvent(new LoanCaseAssignedEvent(tenantId, loanId, before.assignedTo(), after.assignedTo()));
            }
            return saved;
        });
    }

    private void validate(Long tenantId, String actor, LoanCase loanCase, LoanCase.State before, LoanCase.State after,
                          String reason) {
        if (after.status() == null) {
            throw CaseRuleException.unprocessable("A status is required.");
        }
        if (!before.status().canTransitionTo(after.status())) {
            throw CaseRuleException.unprocessable("A case cannot move from " + before.status() + " to " + after.status() + ".");
        }
        if (before.status().requiresReason(after.status()) && blankToNull(reason) == null) {
            throw CaseRuleException.unprocessable("A reason is required to move a case from " + before.status()
                    + " to " + after.status() + ".");
        }
        if (before.status() == LoanCaseStatus.ESCALATED && after.status() == LoanCaseStatus.CLEARED
                && actor.equals(loanCase.getEscalatedBy())) {
            throw CaseRuleException.unprocessable("Four-eyes rule: a case must be cleared by someone other than the person who escalated it.");
        }
        if (after.assignedTo() != null && !after.assignedTo().equals(before.assignedTo())
                && userService.findActiveStaff(tenantId, after.assignedTo()).isEmpty()) {
            throw CaseRuleException.unprocessable("'" + after.assignedTo() + "' is not an active analyst or admin in your organization.");
        }
    }

    /** Most recent history entries for one case, newest first. */
    public List<LoanCaseEvent> history(Long tenantId, String loanId, int limit) {
        return eventRepository.findByTenantIdAndLoanIdOrderByOccurredAtDesc(tenantId, loanId,
                PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIMIT))));
    }

    /** The most recent notes on one loan (capped: the view is for reading, search covers the rest). */
    public List<LoanNote> notesFor(Long tenantId, String loanId) {
        return loanNoteRepository.findByTenantIdAndLoanIdOrderByCreatedAtDesc(tenantId, loanId, PageRequest.of(0, MAX_NOTES_SHOWN));
    }

    /** Adds a note; the note and the event it raises commit together. */
    public void addNote(Long tenantId, String loanId, String author, String text) {
        transactionTemplate.executeWithoutResult(tx -> {
            loanNoteRepository.save(new LoanNote(tenantId, loanId, author, text));
            String assignee = getOrCreate(tenantId, loanId).getAssignedTo();
            eventPublisher.publishEvent(new LoanCaseNoteAddedEvent(tenantId, loanId, author, text, assignee));
        });
    }

    /** A loan this tenant can act on: one in its portfolio (or the demo catalog), or one it already
     * has a case for. Other tenants' loans are indistinguishable from nonexistent ones. */
    public boolean isKnownLoan(Long tenantId, String loanId) {
        return loanId != null && !loanId.isBlank() && loanId.length() <= 80
                && (loanCatalogService.find(loanId) != null || loanCaseRepository.existsById(new LoanCaseId(tenantId, loanId)));
    }

    public List<LoanCase> recentCasesForTenant(Long tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return loanCaseRepository.findAllByIdTenantIdOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, bounded)).getContent();
    }

    /** Streams every case of the tenant in loan-id order, a page at a time (keyset, no OFFSET), so an
     * export of any size uses bounded memory. */
    public void forEachCase(Long tenantId, Consumer<LoanCase> sink) {
        String after = "";
        while (true) {
            List<LoanCase> page = loanCaseRepository.findByIdTenantIdAndIdLoanIdGreaterThanOrderByIdLoanIdAsc(
                    tenantId, after, PageRequest.of(0, 1000));
            page.forEach(sink);
            if (page.size() < 1000) {
                return;
            }
            after = page.get(page.size() - 1).getLoanId();
        }
    }

    public List<LoanNote> recentNotesForTenant(Long tenantId) {
        return loanNoteRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** Composable filtered search (Specification pattern); page size and depth are capped server-side. */
    public Page<LoanCase> search(Long tenantId, LoanCaseFilter filter, String propertyState, int page, int size) {
        LoanCaseFilter effective = filter;
        if (propertyState != null && !propertyState.isBlank()) {
            effective = new LoanCaseFilter(filter.status(), filter.flagged(), filter.assignedTo(),
                    loanCatalogService.loanIdsInState(propertyState), filter.updatedFrom(), filter.updatedTo());
        }
        return loanCaseRepository.findAll(LoanCaseSpecs.forTenant(tenantId, effective),
                PageRequest.of(PageResult.boundedPage(page), PageResult.boundedSize(size), Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    public Page<LoanNote> searchNotes(Long tenantId, String text, String author, String loanId,
                                      Instant from, Instant to, int page, int size) {
        return loanNoteRepository.findAll(LoanNoteSpecs.forTenant(tenantId, text, author, loanId, from, to),
                PageRequest.of(PageResult.boundedPage(page), PageResult.boundedSize(size), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /** Note texts for ML jobs (newest first), capped. */
    public List<String> noteTextsForTenant(Long tenantId, int limit) {
        return loanNoteRepository.findAll(LoanNoteSpecs.tenant(tenantId),
                        PageRequest.of(0, Math.max(1, Math.min(limit, 5000)), Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent().stream().map(LoanNote::getText).toList();
    }

    /** Null fields mean "leave as is"; {@code unassign} clears the assignee. */
    public record BulkChange(LoanCaseStatus status, String assignedTo, Boolean flagged, boolean unassign, String reason) {
    }

    public record BulkItemResult(String loanId, boolean ok, String error) {
    }

    /**
     * Applies one change to many cases. Each item commits independently and is reported on its own: a
     * rule violation or unknown loan on one never fails the batch. Each change is computed from that
     * case's state inside its own transaction, so a concurrent edit is never overwritten with stale data.
     */
    public List<BulkItemResult> bulkUpdate(Long tenantId, String actor, List<String> loanIds, BulkChange change) {
        if (loanIds.size() > MAX_BULK) {
            throw new IllegalArgumentException("At most " + MAX_BULK + " cases per bulk update");
        }
        List<BulkItemResult> results = new ArrayList<>();
        for (String loanId : new LinkedHashSet<>(loanIds)) {
            if (!isKnownLoan(tenantId, loanId)) {
                results.add(new BulkItemResult(loanId, false, "Unknown loan"));
                continue;
            }
            try {
                String newAssignee = blankToNull(change.assignedTo());
                update(tenantId, loanId, actor, null, change.reason(), s -> new LoanCase.State(
                        change.status() != null ? change.status() : s.status(),
                        change.unassign() ? null : (newAssignee != null ? newAssignee : s.assignedTo()),
                        change.flagged() != null ? change.flagged() : s.flagged()));
                results.add(new BulkItemResult(loanId, true, null));
            } catch (CaseRuleException e) {
                results.add(new BulkItemResult(loanId, false, e.getMessage()));
            } catch (RuntimeException e) {
                results.add(new BulkItemResult(loanId, false, "Update failed: " + e.getClass().getSimpleName()));
            }
        }
        return results;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
