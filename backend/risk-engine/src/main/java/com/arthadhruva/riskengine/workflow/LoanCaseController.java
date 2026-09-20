package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.export.CsvWriter;
import com.arthadhruva.riskengine.tenant.TenantContext;
import com.arthadhruva.riskengine.search.PageResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Human case-tracking for a loan (status/assignment/flag + a notes thread) -- separate from
 * every model-serving endpoint, since none of this is computed. ANALYST/ADMIN only (the default
 * access rule) -- a CLIENT has no business seeing an internal review workflow about their own
 * loan.
 */
@RestController
public class LoanCaseController {

    private final LoanCaseService loanCaseService;

    public LoanCaseController(LoanCaseService loanCaseService) {
        this.loanCaseService = loanCaseService;
    }

    @GetMapping("/loans/{loanId}/case")
    public LoanCaseView getCase(@PathVariable String loanId) {
        Long tenantId = TenantContext.get();
        LoanCase loanCase = loanCaseService.getOrCreate(tenantId, loanId);
        List<LoanNote> notes = loanCaseService.notesFor(tenantId, loanId);
        return LoanCaseView.of(loanCase, notes);
    }

    @PostMapping("/loans/{loanId}/case")
    public LoanCaseView updateCase(@PathVariable String loanId, @RequestBody UpdateCaseRequest request) {
        Long tenantId = TenantContext.get();
        LoanCase loanCase = loanCaseService.update(tenantId, loanId, request.status(), request.assignedTo(), request.flagged());
        List<LoanNote> notes = loanCaseService.notesFor(tenantId, loanId);
        return LoanCaseView.of(loanCase, notes);
    }

    @PostMapping("/loans/{loanId}/notes")
    public LoanCaseView addNote(@PathVariable String loanId, @RequestBody AddNoteRequest request, Authentication authentication) {
        Long tenantId = TenantContext.get();
        loanCaseService.addNote(tenantId, loanId, authentication.getName(), request.text());
        LoanCase loanCase = loanCaseService.getOrCreate(tenantId, loanId);
        List<LoanNote> notes = loanCaseService.notesFor(tenantId, loanId);
        return LoanCaseView.of(loanCase, notes);
    }

    /** Every case that's ever had status/assignment/flag set explicitly (a loan only gets a row
     * here once someone acts on it -- see the lazy-create in getCase/updateCase) -- the "my
     * cases" / "flagged loans" view an analyst actually works from day to day, instead of opening
     * one loan at a time to check. */
    @GetMapping("/loan-cases")
    public List<LoanCaseSummary> allCases(@RequestParam(defaultValue = "50") int limit) {
        return loanCaseService.recentCasesForTenant(TenantContext.get(), limit).stream()
                .map(c -> new LoanCaseSummary(c.getLoanId(), c.getStatus(), c.getAssignedTo(), c.isFlagged(), c.getUpdatedAt()))
                .toList();
    }

    /** Combined-filter search (all params optional, AND-ed): status, flagged, assignee, property
     * state, updated-at range. Tenant scoping is applied inside the composed query, not by the caller. */
    @GetMapping("/loan-cases/search")
    public PageResult<LoanCaseSummary> searchCases(
            @RequestParam(required = false) LoanCaseStatus status,
            @RequestParam(required = false) Boolean flagged,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) java.time.Instant updatedFrom,
            @RequestParam(required = false) java.time.Instant updatedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        LoanCaseFilter filter = new LoanCaseFilter(status, flagged, assignedTo, null, updatedFrom, updatedTo);
        return PageResult.of(loanCaseService.search(TenantContext.get(), filter, state, page, size),
                c -> new LoanCaseSummary(c.getLoanId(), c.getStatus(), c.getAssignedTo(), c.isFlagged(), c.getUpdatedAt()));
    }

    @GetMapping("/loan-notes/search")
    public PageResult<RecentNoteView> searchNotes(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String loanId,
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResult.of(loanCaseService.searchNotes(TenantContext.get(), q, author, loanId, from, to, page, size),
                n -> new RecentNoteView(n.getLoanId(), n.getAuthor(), n.getText(), n.getCreatedAt()));
    }

    /** Null fields in {@code change} mean "leave as is". Per-item results; the batch never fails
     * because one item did. */
    @PostMapping("/loan-cases/bulk")
    public List<LoanCaseService.BulkItemResult> bulkUpdate(@RequestBody BulkUpdateRequest request) {
        return loanCaseService.bulkUpdate(TenantContext.get(), request.loanIds(),
                new LoanCaseService.BulkChange(request.status(), request.assignedTo(), request.flagged()));
    }

    public record BulkUpdateRequest(List<String> loanIds, LoanCaseStatus status, String assignedTo, Boolean flagged) {
    }

    /** Same data as {@code GET /loan-cases}, at the service's max page size, as a downloadable
     * CSV. */
    @GetMapping("/loan-cases/export")
    public ResponseEntity<String> exportCases() {
        List<List<String>> rows = loanCaseService.recentCasesForTenant(TenantContext.get(), Integer.MAX_VALUE).stream()
                .map(c -> List.of(c.getLoanId(), c.getStatus().name(), c.getAssignedTo() == null ? "" : c.getAssignedTo(),
                        String.valueOf(c.isFlagged()), c.getUpdatedAt().toString()))
                .toList();
        String csv = CsvWriter.write(List.of("loanId", "status", "assignedTo", "flagged", "updatedAt"), rows);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("loan-cases.csv").build().toString())
                .body(csv);
    }

    /** The most recent notes across every loan, newest first -- a simple activity feed without
     * needing a separate audit mechanism, since a note is already an immutable timestamped
     * record of "someone did something about this loan." */
    @GetMapping("/loan-notes/recent")
    public List<RecentNoteView> recentNotes() {
        return loanCaseService.recentNotesForTenant(TenantContext.get()).stream()
                .map(n -> new RecentNoteView(n.getLoanId(), n.getAuthor(), n.getText(), n.getCreatedAt()))
                .toList();
    }

    public record LoanCaseSummary(
            String loanId, LoanCaseStatus status, String assignedTo, boolean flagged, java.time.Instant updatedAt
    ) {
    }

    public record RecentNoteView(String loanId, String author, String text, java.time.Instant createdAt) {
    }

    public record UpdateCaseRequest(@NotNull LoanCaseStatus status, String assignedTo, boolean flagged) {
    }

    public record AddNoteRequest(@NotBlank String text) {
    }

    public record NoteView(String author, String text, java.time.Instant createdAt) {
        static NoteView of(LoanNote note) {
            return new NoteView(note.getAuthor(), note.getText(), note.getCreatedAt());
        }
    }

    public record LoanCaseView(
            String loanId, LoanCaseStatus status, String assignedTo, boolean flagged,
            java.time.Instant updatedAt, List<NoteView> notes
    ) {
        static LoanCaseView of(LoanCase loanCase, List<LoanNote> notes) {
            return new LoanCaseView(loanCase.getLoanId(), loanCase.getStatus(), loanCase.getAssignedTo(),
                    loanCase.isFlagged(), loanCase.getUpdatedAt(), notes.stream().map(NoteView::of).toList());
        }
    }
}
