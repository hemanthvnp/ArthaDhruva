package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.survival.TermStructureEngine.InvalidProjectionException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Probability weights over the scenarios of one comparison, and the weighted sum of a figure across them.
 *
 * <p>The weights are the caller's judgement about how likely each scenario is; nothing here estimates
 * them. A weighted figure is {@code sum_s w_s x figure_s}, each {@code figure_s} being exactly what the
 * request reports for scenario {@code s} (so a scenario's allowance is staged on its own path). The sum
 * is taken in the order the scenarios are compared and zero-weight terms are skipped, which makes the
 * result independent of the order the weights were sent in, and makes a weight of 1 return that
 * scenario's figure bit for bit.
 */
public final class ScenarioWeights {

    /** How far the weights may be from summing to 1: room for decimals typed as 0.33, 0.33, 0.34, no more. */
    static final double SUM_TOLERANCE = 1e-6;

    private ScenarioWeights() {
    }

    /**
     * The weights aligned with {@code scenarios}; a scenario that is not named carries 0.
     *
     * @param weights   scenario name to weight; names are trimmed and matched case-insensitively
     * @param scenarios the scenarios being compared, in the order their figures are reported
     * @throws InvalidProjectionException if the map is empty, names a scenario that is not compared (or one
     *                                    twice), holds a negative or non-finite weight, or does not sum to 1
     */
    public static double[] resolve(Map<String, Double> weights, List<String> scenarios) {
        if (weights == null || weights.isEmpty()) {
            throw new InvalidProjectionException("weights must name at least one of the compared scenarios: " + scenarios);
        }
        double[] aligned = new double[scenarios.size()];
        boolean[] seen = new boolean[scenarios.size()];
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
            int at = scenarios.indexOf(name);
            if (at < 0) {
                throw new InvalidProjectionException("weights names '" + name + "', which is not one of the compared scenarios: " + scenarios);
            }
            if (seen[at]) {
                throw new InvalidProjectionException("weights names " + name + " more than once");
            }
            Double weight = entry.getValue();
            if (weight == null || !Double.isFinite(weight) || weight < 0.0) {
                throw new InvalidProjectionException("the weight of " + name + " must be a number that is not negative, got " + weight);
            }
            seen[at] = true;
            aligned[at] = weight;
        }
        double sum = 0.0;
        for (double w : aligned) {
            sum += w;
        }
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new InvalidProjectionException("weights must sum to 1, got " + sum);
        }
        return aligned;
    }

    /** {@code sum_s w_s x figure_s}, left to right, skipping scenarios with no weight. */
    public static double combine(double[] weights, double[] figure) {
        if (weights.length != figure.length) {
            throw new IllegalArgumentException("one figure per weighted scenario: " + weights.length + " weights, " + figure.length + " figures");
        }
        double sum = 0.0;
        boolean single = false;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 1.0) {
                single = true;  // all the weight on one scenario: its figure, not 1.0 * figure plus rounding
                sum = figure[i];
            }
        }
        if (single) {
            return sum;
        }
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] != 0.0) {
                sum += weights[i] * figure[i];
            }
        }
        return sum;
    }

    /** The weights by scenario name, in compared order, as they are echoed in a response. */
    public static Map<String, Double> named(double[] weights, List<String> scenarios) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < weights.length; i++) {
            out.put(scenarios.get(i), weights[i]);
        }
        return out;
    }
}
