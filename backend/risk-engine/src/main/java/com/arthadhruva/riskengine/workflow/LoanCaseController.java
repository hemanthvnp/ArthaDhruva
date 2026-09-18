package com.arthadhruva.riskengine.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final LoanCaseRepository loanCaseRepository;
    private final LoanNoteRepository loanNoteRepository;

    public LoanCaseController(LoanCaseRepository loanCaseRepository, LoanNoteRepository loanNoteRepository) {
        this.loanCaseRepository = loanCaseRepository;
        this.loanNoteRepository = loanNoteRepository;
    }

    /** Lazily creates a NEW/unflagged/unassigned case on first access -- a loan doesn't need to
     * be pre-registered here, the same way a fresh loan_score row only appears once /score is
     * first called with a loanId. */
    @GetMapping("/loans/{loanId}/case")
    public LoanCaseView getCase(@PathVariable String loanId) {
        LoanCase loanCase = loanCaseRepository.findById(loanId).orElseGet(() -> new LoanCase(loanId));
        List<LoanNote> notes = loanNoteRepository.findByLoanIdOrderByCreatedAtDesc(loanId);
        return LoanCaseView.of(loanCase, notes);
    }

    @PostMapping("/loans/{loanId}/case")
    public LoanCaseView updateCase(@PathVariable String loanId, @RequestBody UpdateCaseRequest request) {
        LoanCase loanCase = loanCaseRepository.findById(loanId).orElseGet(() -> new LoanCase(loanId));
        loanCase.update(request.status(), request.assignedTo(), request.flagged());
        loanCaseRepository.save(loanCase);
        List<LoanNote> notes = loanNoteRepository.findByLoanIdOrderByCreatedAtDesc(loanId);
        return LoanCaseView.of(loanCase, notes);
    }

    @PostMapping("/loans/{loanId}/notes")
    public LoanCaseView addNote(@PathVariable String loanId, @RequestBody AddNoteRequest request, Authentication authentication) {
        loanNoteRepository.save(new LoanNote(loanId, authentication.getName(), request.text()));
        LoanCase loanCase = loanCaseRepository.findById(loanId).orElseGet(() -> new LoanCase(loanId));
        List<LoanNote> notes = loanNoteRepository.findByLoanIdOrderByCreatedAtDesc(loanId);
        return LoanCaseView.of(loanCase, notes);
    }

    /** Every case that's ever had status/assignment/flag set explicitly (a loan only gets a row
     * here once someone acts on it -- see the lazy-create in getCase/updateCase) -- the "my
     * cases" / "flagged loans" view an analyst actually works from day to day, instead of opening
     * one loan at a time to check. */
    @GetMapping("/loan-cases")
    public List<LoanCaseSummary> allCases() {
        return loanCaseRepository.findAll().stream()
                .map(c -> new LoanCaseSummary(c.getLoanId(), c.getStatus(), c.getAssignedTo(), c.isFlagged(), c.getUpdatedAt()))
                .toList();
    }

    /** The most recent notes across every loan, newest first -- a simple activity feed without
     * needing a separate audit mechanism, since a note is already an immutable timestamped
     * record of "someone did something about this loan." */
    @GetMapping("/loan-notes/recent")
    public List<RecentNoteView> recentNotes() {
        return loanNoteRepository.findTop50ByOrderByCreatedAtDesc().stream()
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
