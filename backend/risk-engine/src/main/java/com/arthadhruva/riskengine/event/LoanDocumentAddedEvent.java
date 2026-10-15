package com.arthadhruva.riskengine.event;

/** Published when a document is attached to a loan, by staff or by the borrower. Carries only who uploaded it
 * and to which loan: listeners decide who should hear about it. */
public class LoanDocumentAddedEvent extends DomainEvent {

    private final String loanId;
    private final String uploadedBy;

    public LoanDocumentAddedEvent(Long tenantId, String loanId, String uploadedBy) {
        super(tenantId);
        this.loanId = loanId;
        this.uploadedBy = uploadedBy;
    }

    public String getLoanId() {
        return loanId;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }
}
