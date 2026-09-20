package com.arthadhruva.riskengine.automation;

/** Serialized form of one action inside a rule ({@code param}: assignee / recipient username). */
public record ActionSpec(ActionType type, String param) {
}
