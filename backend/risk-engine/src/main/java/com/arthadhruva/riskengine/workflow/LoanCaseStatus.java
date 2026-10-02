package com.arthadhruva.riskengine.workflow;

import java.util.EnumSet;
import java.util.Set;

/**
 * The review lifecycle an analyst moves a loan through after it is scored -- human-driven, never set by
 * a model. Transitions are explicit:
 * <pre>
 *   NEW       -> REVIEWED | ESCALATED | CLEARED
 *   REVIEWED  -> ESCALATED | CLEARED
 *   ESCALATED -> REVIEWED | CLEARED     (clearing requires a second person: see LoanCaseService)
 *   CLEARED   -> REVIEWED               (reopen)
 * </pre>
 * Staying in the same status is always allowed (e.g. reassigning or flagging).
 */
public enum LoanCaseStatus {
    NEW, REVIEWED, ESCALATED, CLEARED;

    private Set<LoanCaseStatus> next() {
        return switch (this) {
            case NEW -> EnumSet.of(REVIEWED, ESCALATED, CLEARED);
            case REVIEWED -> EnumSet.of(ESCALATED, CLEARED);
            case ESCALATED -> EnumSet.of(REVIEWED, CLEARED);
            case CLEARED -> EnumSet.of(REVIEWED);
        };
    }

    public boolean canTransitionTo(LoanCaseStatus target) {
        return target == this || next().contains(target);
    }

    /** Transitions that must carry a written reason: escalating, clearing an escalation, reopening. */
    public boolean requiresReason(LoanCaseStatus target) {
        return (target == ESCALATED && this != ESCALATED)
                || (this == ESCALATED && target == CLEARED)
                || (this == CLEARED && target == REVIEWED);
    }
}
