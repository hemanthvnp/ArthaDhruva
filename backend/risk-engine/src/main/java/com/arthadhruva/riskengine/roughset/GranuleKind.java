package com.arthadhruva.riskengine.roughset;

/** How the variants a loan is judged over are chosen. */
public enum GranuleKind {
    /** Bounded perturbations of one target scenario: "is this decision fragile to the stress details?" */
    PERTURBATION,
    /** Caller-listed built-in scenarios: "does the answer hold across all of these scenarios?" */
    SCENARIO_SET
}
