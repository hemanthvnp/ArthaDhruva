package com.arthadhruva.riskengine.roughset;

/**
 * Where a loan falls in a three-way decision about whether its stressed risk is high.
 *
 * <p>In rough-set terms, with "high" the concept being approximated: {@link #CERTAIN_HIGH} is the lower
 * approximation (high in every variant, up to the tolerance), {@link #CERTAIN_LOW} is the complement of
 * the upper approximation (high in none), and {@link #BOUNDARY} is the difference between the two.
 */
public enum Region {
    CERTAIN_HIGH, CERTAIN_LOW, BOUNDARY
}
