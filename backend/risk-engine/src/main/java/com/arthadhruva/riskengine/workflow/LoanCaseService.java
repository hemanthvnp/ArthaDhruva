package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.event.LoanCaseAssignedEvent;
import com.arthadhruva.riskengine.event.LoanCaseNoteAddedEvent;
import com.arthadhruva.riskengine.event.LoanCaseUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.search.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * The {@code workflow} module's façade for {@link LoanCase}/{@link LoanNote} -- {@link
 * LoanCaseRepository}/{@link LoanNoteRepository} are private to this package;
 * {@link LoanCaseController} goes through here instead of touching them directly.
 *
 * <p>Side effects of a case change (notifications today; automation rules and webhooks in
 * future phases) are published as domain events (design decision 1) rather than called
 * directly -- this module knows nothing about who, if anyone, reacts to them.
 */
@Service
public class LoanCaseService {

    private static final int MAX_LIMIT = 500;

    private final LoanCaseRepository loanCaseRepository;
    private final LoanNoteRepository loanNoteRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LoanCatalogService loanCatalogService;
    private final TransactionTemplate transactionTemplate;

    public LoanCaseService(LoanCaseRepository loanCaseRepository, LoanNoteRepository loanNoteRepository,
                            ApplicationEventPublisher eventPublisher, LoanCatalogService loanCatalogService,
                            TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.loanCatalogService = loanCatalogService;
        this.loanCaseRepository = loanCaseRepository;
        this.loanNoteRepository = loanNoteRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Lazily creates a NEW/unflagged/unassigned case on first access -- a loan doesn't need to
     * be pre-registered here, the same way a fresh loan_score row only appears once /score is
     * first called with a loanId. */
    public LoanCase getOrCreate(Long tenantId, String loanId) {
        LoanCaseId id = new LoanCaseId(tenantId, loanId);
        return loanCaseRepository.findById(id).orElseGet(() -> new LoanCase(id));
    }

    /** The save and the events it raises share one transaction (TransactionTemplate rather than
     * @Transactional so internal callers like bulkUpdate get it too): transactional listeners, e.g.
     * the webhook outbox writer, then commit or roll back atomically with the case row. */
    public LoanCase update(Long tenantId, String loanId, LoanCaseStatus status, String assignedTo, boolean flagged) {
        return transactionTemplate.execute(tx -> {
            LoanCase loanCase = getOrCreate(tenantId, loanId);
            String previousAssignee = loanCase.getAssignedTo();
            boolean wasFlagged = loanCase.isFlagged();
            loanCase.update(status, assignedTo, flagged);
            LoanCase saved = loanCaseRepository.save(loanCase);

            eventPublisher.publishEvent(new LoanCaseUpdatedEvent(tenantId, loanId, status, assignedTo, flagged, wasFlagged));
            if (assignedTo != null && !assignedTo.equals(previousAssignee)) {
                eventPublisher.publishEvent(new LoanCaseAssignedEvent(tenantId, loanId, previousAssignee, assignedTo));
            }
            return saved;
        });
    }

    public List<LoanNote> notesFor(Long tenantId, String loanId) {
        return loanNoteRepository.findByTenantIdAndLoanIdOrderByCreatedAtDesc(tenantId, loanId);
    }

    public void addNote(Long tenantId, String loanId, String author, String text) {
        loanNoteRepository.save(new LoanNote(tenantId, loanId, author, text));

        String assignee = getOrCreate(tenantId, loanId).getAssignedTo();
        eventPublisher.publishEvent(new LoanCaseNoteAddedEvent(tenantId, loanId, author, text, assignee));
    }

    /** Every case in a tenant that's ever had status/assignment/flag set explicitly (a loan only
     * gets a row here once someone acts on it -- see {@link #getOrCreate}). */
    public List<LoanCase> recentCasesForTenant(Long tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return loanCaseRepository.findAllByIdTenantIdOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, bounded)).getContent();
    }

    /** The most recent notes across every loan in a tenant, newest first. */
    public List<LoanNote> recentNotesForTenant(Long tenantId) {
        return loanNoteRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** Composable filtered search (Specification pattern); page size is capped server-side. */
    public Page<LoanCase> search(Long tenantId, LoanCaseFilter filter, String propertyState, int page, int size) {
        LoanCaseFilter effective = filter;
        if (propertyState != null && !propertyState.isBlank()) {
            effective = new LoanCaseFilter(filter.status(), filter.flagged(), filter.assignedTo(),
                    loanCatalogService.loanIdsInState(propertyState), filter.updatedFrom(), filter.updatedTo());
        }
        return loanCaseRepository.findAll(LoanCaseSpecs.forTenant(tenantId, effective),
                PageRequest.of(Math.max(0, page), PageResult.boundedSize(size), Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    public Page<LoanNote> searchNotes(Long tenantId, String text, String author, String loanId,
                                      java.time.Instant from, java.time.Instant to, int page, int size) {
        return loanNoteRepository.findAll(LoanNoteSpecs.forTenant(tenantId, text, author, loanId, from, to),
                PageRequest.of(Math.max(0, page), PageResult.boundedSize(size), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /** Note texts for ML jobs (newest first), capped. */
    public List<String> noteTextsForTenant(Long tenantId, int limit) {
        return loanNoteRepository.findAll(LoanNoteSpecs.tenant(tenantId),
                PageRequest.of(0, Math.max(1, Math.min(limit, 5000)), Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent().stream().map(LoanNote::getText).toList();
    }

    public record BulkChange(LoanCaseStatus status, String assignedTo, Boolean flagged) {
    }

    public record BulkItemResult(String loanId, boolean ok, String error) {
    }

    public static final int MAX_BULK = 100;

    /**
     * Applies one change to many cases. Each item is independent: a bad id or a concurrent-edit
     * conflict on one is reported for that item only and never fails the batch. Per-item updates
     * go through {@link #update}, so each publishes its own events (e.g. an assignment
     * notification per case). A loan is "known" if the tenant already has a case for it or it is in
     * the catalog; anything else (including another tenant's loan) is reported as unknown.
     */
    public List<BulkItemResult> bulkUpdate(Long tenantId, List<String> loanIds, BulkChange change) {
        if (loanIds.size() > MAX_BULK) {
            throw new IllegalArgumentException("At most " + MAX_BULK + " cases per bulk update");
        }
        List<BulkItemResult> results = new java.util.ArrayList<>();
        for (String loanId : new java.util.LinkedHashSet<>(loanIds)) {
            try {
                boolean known = loanCaseRepository.existsById(new LoanCaseId(tenantId, loanId))
                        || loanCatalogService.find(loanId) != null;
                if (!known) {
                    results.add(new BulkItemResult(loanId, false, "Unknown loan"));
                    continue;
                }
                LoanCase current = getOrCreate(tenantId, loanId);
                update(tenantId, loanId,
                        change.status() != null ? change.status() : current.getStatus(),
                        change.assignedTo() != null ? change.assignedTo() : current.getAssignedTo(),
                        change.flagged() != null ? change.flagged() : current.isFlagged());
                results.add(new BulkItemResult(loanId, true, null));
            } catch (Exception e) {
                results.add(new BulkItemResult(loanId, false, "Update failed: " + e.getClass().getSimpleName()));
            }
        }
        return results;
    }
}
