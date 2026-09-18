package com.arthadhruva.riskengine.workflow;

/** The lifecycle a real analyst pushes a loan through after it's been scored, not something a
 * model produces -- purely human-driven case tracking. */
public enum LoanCaseStatus {
    NEW, REVIEWED, ESCALATED, CLEARED
}
