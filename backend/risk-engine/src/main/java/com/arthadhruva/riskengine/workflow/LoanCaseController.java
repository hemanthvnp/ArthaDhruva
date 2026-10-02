package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.export.CsvWriter;
import com.arthadhruva.riskengine.search.PageResult;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human case-tracking for a loan (status, assignment, flag, notes, history). ANALYST/ADMIN only (the
 * default access rule): a borrower never sees the internal review workflow about their own loan.
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
        return LoanCaseView.of(loanCaseService.getOrCreate(tenantId, loanId), loanCaseService.notesFor(tenantId, loanId),
                loanCaseService.history(tenantId, loanId, 20));
    }

    /** Full replacement of the case state, based on {@code expectedVersion} (the version the client
     * displayed): refused with 409 and the current state if someone else changed it meanwhile. */
    @PostMapping("/loans/{loanId}/case")
    public ResponseEntity<?> updateCase(@PathVariable String loanId, @Valid @RequestBody UpdateCaseRequest request,
                                        Authentication authentication) {
        Long tenantId = TenantContext.get();
        if (!loanCaseService.isKnownLoan(tenantId, loanId)) {
            return ResponseEntity.status(404).body(Map.of("error", "Unknown loan"));
        }
        String assignee = request.assignedTo() == null || request.assignedTo().isBlank() ? null : request.assignedTo().trim();
        LoanCase loanCase = loanCaseService.update(tenantId, loanId, authentication.getName(), request.expectedVersion(),
                request.reason(), current -> new LoanCase.State(request.status(), assignee, request.flagged()));
        return ResponseEntity.ok(LoanCaseView.of(loanCase, loanCaseService.notesFor(tenantId, loanId),
                loanCaseService.history(tenantId, loanId, 20)));
    }

    @PostMapping("/loans/{loanId}/notes")
    public ResponseEntity<?> addNote(@PathVariable String loanId, @Valid @RequestBody AddNoteRequest request,
                                     Authentication authentication) {
        Long tenantId = TenantContext.get();
        if (!loanCaseService.isKnownLoan(tenantId, loanId)) {
            return ResponseEntity.status(404).body(Map.of("error", "Unknown loan"));
        }
        loanCaseService.addNote(tenantId, loanId, authentication.getName(), request.text().strip());
        return ResponseEntity.ok(LoanCaseView.of(loanCaseService.getOrCreate(tenantId, loanId),
                loanCaseService.notesFor(tenantId, loanId), loanCaseService.history(tenantId, loanId, 20)));
    }

    @GetMapping("/loans/{loanId}/case/history")
    public List<CaseEventView> history(@PathVariable String loanId, @RequestParam(defaultValue = "100") int limit) {
        return loanCaseService.history(TenantContext.get(), loanId, limit).stream().map(CaseEventView::of).toList();
    }

    @ExceptionHandler(CaseRuleException.class)
    public ResponseEntity<Map<String, Object>> onRuleViolation(CaseRuleException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        if (e.getCurrent() != null) {
            LoanCase c = e.getCurrent();
            body.put("current", new LoanCaseSummary(c.getLoanId(), c.getStatus(), c.getAssignedTo(), c.isFlagged(),
                    c.getUpdatedAt(), c.getVersion()));
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @GetMapping("/loan-cases")
    public List<LoanCaseSummary> allCases(@RequestParam(defaultValue = "50") int limit) {
        return loanCaseService.recentCasesForTenant(TenantContext.get(), limit).stream().map(LoanCaseSummary::of).toList();
    }

    /** Combined-filter search (all params optional, AND-ed). Tenant scoping is inside the composed query. */
    @GetMapping("/loan-cases/search")
    public PageResult<LoanCaseSummary> searchCases(
            @RequestParam(required = false) LoanCaseStatus status,
            @RequestParam(required = false) Boolean flagged,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Instant updatedFrom,
            @RequestParam(required = false) Instant updatedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        LoanCaseFilter filter = new LoanCaseFilter(status, flagged, assignedTo, null, updatedFrom, updatedTo);
        return PageResult.of(loanCaseService.search(TenantContext.get(), filter, state, page, size), LoanCaseSummary::of);
    }

    @GetMapping("/loan-notes/search")
    public PageResult<RecentNoteView> searchNotes(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String loanId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        String text = q == null ? null : (q.length() > 200 ? q.substring(0, 200) : q);
        return PageResult.of(loanCaseService.searchNotes(TenantContext.get(), text, author, loanId, from, to, page, size),
                n -> new RecentNoteView(n.getLoanId(), n.getAuthor(), n.getText(), n.getCreatedAt()));
    }

    /** Null fields mean "leave as is"; per-item results -- the batch never fails because one item did. */
    @PostMapping("/loan-cases/bulk")
    public List<LoanCaseService.BulkItemResult> bulkUpdate(@Valid @RequestBody BulkUpdateRequest request,
                                                           Authentication authentication) {
        return loanCaseService.bulkUpdate(TenantContext.get(), authentication.getName(), request.loanIds(),
                new LoanCaseService.BulkChange(request.status(), request.assignedTo(), request.flagged(),
                        Boolean.TRUE.equals(request.unassign()), request.reason()));
    }

    /** Every case of the organization as CSV, streamed (keyset pages) so memory stays flat at any size. */
    @GetMapping("/loan-cases/export")
    public ResponseEntity<StreamingResponseBody> exportCases() {
        Long tenantId = TenantContext.get();
        StreamingResponseBody body = out -> {
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                var csv = new CsvWriter.Streaming(writer, List.of("loanId", "status", "assignedTo", "flagged", "updatedAt", "version"));
                com.arthadhruva.riskengine.tenant.TenantContext.set(tenantId);
                try {
                    loanCaseService.forEachCase(tenantId, c -> csv.row(List.of(c.getLoanId(), c.getStatus().name(),
                            c.getAssignedTo() == null ? "" : c.getAssignedTo(), String.valueOf(c.isFlagged()),
                            c.getUpdatedAt().toString(), String.valueOf(c.getVersion()))));
                } finally {
                    com.arthadhruva.riskengine.tenant.TenantContext.clear();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("loan-cases.csv").build().toString())
                .body(body);
    }

    @GetMapping("/loan-notes/recent")
    public List<RecentNoteView> recentNotes() {
        return loanCaseService.recentNotesForTenant(TenantContext.get()).stream()
                .map(n -> new RecentNoteView(n.getLoanId(), n.getAuthor(), n.getText(), n.getCreatedAt()))
                .toList();
    }

    public record BulkUpdateRequest(@NotEmpty @Size(max = LoanCaseService.MAX_BULK) List<@NotBlank @Size(max = 80) String> loanIds,
                                    LoanCaseStatus status, @Size(max = 255) String assignedTo, Boolean flagged,
                                    Boolean unassign, @Size(max = 500) String reason) {
    }

    public record LoanCaseSummary(String loanId, LoanCaseStatus status, String assignedTo, boolean flagged,
                                  Instant updatedAt, long version) {
        static LoanCaseSummary of(LoanCase c) {
            return new LoanCaseSummary(c.getLoanId(), c.getStatus(), c.getAssignedTo(), c.isFlagged(), c.getUpdatedAt(), c.getVersion());
        }
    }

    public record RecentNoteView(String loanId, String author, String text, Instant createdAt) {
    }

    public record UpdateCaseRequest(@NotNull LoanCaseStatus status, @Size(max = 255) String assignedTo, boolean flagged,
                                    Long expectedVersion, @Size(max = 500) String reason) {
    }

    public record AddNoteRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record NoteView(String author, String text, Instant createdAt) {
        static NoteView of(LoanNote note) {
            return new NoteView(note.getAuthor(), note.getText(), note.getCreatedAt());
        }
    }

    public record CaseEventView(String actor, LoanCaseStatus fromStatus, LoanCaseStatus toStatus, String fromAssignee,
                                String toAssignee, Boolean fromFlagged, boolean toFlagged, String reason, Instant occurredAt) {
        static CaseEventView of(LoanCaseEvent e) {
            return new CaseEventView(e.getActor(), e.getFromStatus(), e.getToStatus(), e.getFromAssignee(), e.getToAssignee(),
                    e.getFromFlagged(), e.isToFlagged(), e.getReason(), e.getOccurredAt());
        }
    }

    public record LoanCaseView(String loanId, LoanCaseStatus status, String assignedTo, boolean flagged, Instant updatedAt,
                               long version, String escalatedBy, List<NoteView> notes, List<CaseEventView> history) {
        static LoanCaseView of(LoanCase c, List<LoanNote> notes, List<LoanCaseEvent> history) {
            return new LoanCaseView(c.getLoanId(), c.getStatus(), c.getAssignedTo(), c.isFlagged(), c.getUpdatedAt(),
                    c.getVersion(), c.getEscalatedBy(), notes.stream().map(NoteView::of).toList(),
                    history.stream().map(CaseEventView::of).toList());
        }
    }
}
