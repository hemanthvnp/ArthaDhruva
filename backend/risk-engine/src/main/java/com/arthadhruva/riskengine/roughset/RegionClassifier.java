package com.arthadhruva.riskengine.roughset;

import java.util.Arrays;

/**
 * The three-way decision rule. With {@code k} of {@code n} variants high (metric at or above the
 * threshold) and tolerance {@code beta}: {@code k/n >= 1 - beta} is CERTAIN_HIGH, {@code k/n <= beta} is
 * CERTAIN_LOW, anything else is BOUNDARY. With {@code beta = 0} these are the strict lower approximation
 * of "high" and the complement of its upper approximation. {@code beta < 0.5} keeps the first two apart.
 */
public final class RegionClassifier {

    /** Absorbs floating-point error in {@code 1 - beta} so a boundary case such as 9 of 10 at 0.1 is exact. */
    static final double EPSILON = 1e-12;

    private RegionClassifier() {
    }

    /**
     * @param high      variants at or above the threshold
     * @param members   variants judged
     * @param min       lowest metric across the variants
     * @param median    median metric (mean of the two middle values when the count is even)
     * @param max       highest metric across the variants
     * @param width     {@code max - min}: how far the variants disagree
     */
    public record Classification(Region region, int high, int members, double min, double median, double max, double width) {
    }

    public static Classification classify(double[] metrics, double threshold, double tolerance) {
        if (metrics.length == 0) {
            throw new IllegalArgumentException("A granule needs at least one member");
        }
        int high = 0;
        for (double m : metrics) {
            if (m >= threshold) {
                high++;
            }
        }
        double[] sorted = metrics.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        double share = (double) high / n;
        Region region;
        if (share >= 1.0 - tolerance - EPSILON) {
            region = Region.CERTAIN_HIGH;
        } else if (share <= tolerance + EPSILON) {
            region = Region.CERTAIN_LOW;
        } else {
            region = Region.BOUNDARY;
        }
        return new Classification(region, high, n, sorted[0], median, sorted[n - 1], sorted[n - 1] - sorted[0]);
    }
}
