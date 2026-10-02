package com.arthadhruva.riskengine.workflow;

/**
 * A case change that breaks a workflow rule (an illegal transition, a missing reason, the four-eyes rule,
 * an assignee who cannot take the case) or that was based on a stale version. Carries the HTTP status
 * and, for conflicts, the case as it is now so the client can show it.
 */
public class CaseRuleException extends RuntimeException {

    private final int status;
    private final transient LoanCase current;

    CaseRuleException(int status, String message, LoanCase current) {
        super(message);
        this.status = status;
        this.current = current;
    }

    static CaseRuleException unprocessable(String message) {
        return new CaseRuleException(422, message, null);
    }

    static CaseRuleException conflict(LoanCase current) {
        return new CaseRuleException(409, "This case was changed by someone else. Review the current state and try again.", current);
    }

    public int getStatus() {
        return status;
    }

    public LoanCase getCurrent() {
        return current;
    }
}
