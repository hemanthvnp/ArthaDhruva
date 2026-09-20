package com.arthadhruva.riskengine.insights;

import com.arthadhruva.riskengine.score.LoanFeatures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Unsupervised borrower segmentation: k-means over standardized loan features (each feature scaled
 * to zero mean / unit variance first, otherwise unpaid-balance in the hundreds of thousands would
 * drown out a credit score in the hundreds). Unrelated to the state-level segment-correlation
 * graph: this groups individual loans by their own characteristics.
 */
public final class BorrowerSegmenter {

    public record Trait(String feature, String direction, double zScore) {
    }

    public record Segment(int size, List<Trait> definingTraits, java.util.Map<String, Double> averages) {
    }

    private record Feature(String name, ToDoubleFunction<LoanFeatures> extractor) {
    }

    private static final List<Feature> FEATURES = List.of(
            new Feature("creditScore", l -> l.creditScore()),
            new Feature("originalDti", LoanFeatures::originalDti),
            new Feature("originalUpb", LoanFeatures::originalUpb),
            new Feature("originalLtv", LoanFeatures::originalLtv),
            new Feature("originalCltv", LoanFeatures::originalCltv),
            new Feature("originalInterestRate", LoanFeatures::originalInterestRate),
            new Feature("originalLoanTerm", l -> l.originalLoanTerm()),
            new Feature("miPercent", LoanFeatures::miPercent));

    private BorrowerSegmenter() {
    }

    public static List<Segment> segment(List<LoanFeatures> loans, int k, long seed) {
        int n = loans.size();
        if (n < 2) {
            return List.of();
        }
        int d = FEATURES.size();
        double[][] raw = new double[n][d];
        double[] mean = new double[d];
        double[] std = new double[d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                raw[i][j] = FEATURES.get(j).extractor().applyAsDouble(loans.get(i));
                mean[j] += raw[i][j] / n;
            }
        }
        for (int j = 0; j < d; j++) {
            double variance = 0;
            for (int i = 0; i < n; i++) {
                variance += Math.pow(raw[i][j] - mean[j], 2) / n;
            }
            std[j] = Math.sqrt(variance) < 1e-9 ? 1 : Math.sqrt(variance);
        }
        double[][] z = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                z[i][j] = (raw[i][j] - mean[j]) / std[j];
            }
        }

        KMeans.Result result = KMeans.cluster(z, k, seed, 60);
        List<Segment> segments = new ArrayList<>();
        for (int c = 0; c < result.centroids().length; c++) {
            int size = 0;
            double[] avg = new double[d];
            for (int i = 0; i < n; i++) {
                if (result.assignment()[i] == c) {
                    size++;
                    for (int j = 0; j < d; j++) {
                        avg[j] += raw[i][j];
                    }
                }
            }
            if (size == 0) {
                continue;
            }
            java.util.Map<String, Double> averages = new java.util.LinkedHashMap<>();
            for (int j = 0; j < d; j++) {
                averages.put(FEATURES.get(j).name(), Math.round(avg[j] / size * 100.0) / 100.0);
            }
            double[] centroid = result.centroids()[c];
            List<Trait> traits = java.util.stream.IntStream.range(0, d).boxed()
                    .sorted(Comparator.comparingDouble((Integer j) -> Math.abs(centroid[j])).reversed()).limit(3)
                    .map(j -> new Trait(FEATURES.get(j).name(), centroid[j] >= 0 ? "high" : "low",
                            Math.round(centroid[j] * 100.0) / 100.0)).toList();
            segments.add(new Segment(size, traits, averages));
        }
        segments.sort(Comparator.comparingInt(Segment::size).reversed());
        return segments;
    }
}
