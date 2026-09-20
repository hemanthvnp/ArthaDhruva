package com.arthadhruva.riskengine.event;

/** Published whenever a note is added to a loan case. Carries the case's assignee at the time
 * the note was added (rather than making listeners look it up) since that is exactly the
 * denormalized fact the existing "notify the assignee" behavior needs, and computing it at
 * publish time avoids every listener needing its own dependency back into {@code workflow}. */
public class LoanCaseNoteAddedEvent extends DomainEvent {

    private final String loanId;
    private final String author;
    private final String noteText;
    private final String currentAssignee;

    public LoanCaseNoteAddedEvent(Long tenantId, String loanId, String author, String noteText, String currentAssignee) {
        super(tenantId);
        this.loanId = loanId;
        this.author = author;
        this.noteText = noteText;
        this.currentAssignee = currentAssignee;
    }

    public String getLoanId() {
        return loanId;
    }

    public String getAuthor() {
        return author;
    }

    public String getNoteText() {
        return noteText;
    }

    public String getCurrentAssignee() {
        return currentAssignee;
    }
}
