package com.arthadhruva.riskengine.automation;

/** A rule's predicate over the event's facts: {@code fact[field] op value}. */
record RuleCondition(String field, ConditionOp op, String value) {

    boolean matches(RuleContext context) {
        Object fact = context.facts().get(field);
        if (fact == null) {
            return false;
        }
        if (fact instanceof Number n) {
            int cmp = Double.compare(n.doubleValue(), Double.parseDouble(value));
            return switch (op) {
                case GT -> cmp > 0;
                case GTE -> cmp >= 0;
                case LT -> cmp < 0;
                case LTE -> cmp <= 0;
                case EQ -> cmp == 0;
                case NE -> cmp != 0;
            };
        }
        boolean equal = String.valueOf(fact).equalsIgnoreCase(value);
        return switch (op) {
            case EQ -> equal;
            case NE -> !equal;
            default -> false; // ordering comparisons are meaningless on non-numeric facts
        };
    }
}
