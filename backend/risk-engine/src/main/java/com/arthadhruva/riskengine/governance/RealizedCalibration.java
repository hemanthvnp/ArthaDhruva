package com.arthadhruva.riskengine.governance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Stored scores against what happened: the arithmetic of the comparison, with no database in it.
 *
 * <p>A stored score is the PD model's probability that a loan defaults within {@code horizon} months of the day it was
 * scored. A recorded outcome resolves it (to default or no default within that window) or leaves it unresolved, by these
 * rules, which are the label rules the PD model was trained with (a loan that left the book without defaulting is not a default):
 * <ul>
 *   <li>defaulted after the score and within the horizon: a default (1);</li>
 *   <li>defaulted after the horizon: no default within it (0);</li>
 *   <li>prepaid after the score: no default within the horizon (0);</li>
 *   <li>last seen performing on or after the end of the horizon: no default within it (0);</li>
 *   <li>defaulted or prepaid on or before the day it was scored, or last seen performing before the end of the horizon:
 *       unresolved, and counted, not guessed.</li>
 * </ul>
 * {@code loan_score} keeps only the latest score of a loan, so what is compared is each loan's latest score with what
 * happened after it.
 */
final class RealizedCalibration {

    /** What a recorded outcome says about one stored score. */
    enum Label { DEFAULT, NO_DEFAULT, BEFORE_SCORE, STILL_OPEN }

    static final int MIN_FOR_DECILES = 100;
    static final int MIN_EACH_CLASS_FOR_AUC = 5;

    private RealizedCalibration() {
    }

    static Label label(String outcome, LocalDate observedOn, LocalDate scoredOn, int horizonMonths) {
        LocalDate horizonEnd = scoredOn.plusMonths(horizonMonths);
        return switch (outcome) {
            case "DEFAULTED" -> !observedOn.isAfter(scoredOn) ? Label.BEFORE_SCORE
                    : !observedOn.isAfter(horizonEnd) ? Label.DEFAULT : Label.NO_DEFAULT;
            case "PREPAID" -> !observedOn.isAfter(scoredOn) ? Label.BEFORE_SCORE : Label.NO_DEFAULT;
            case "ACTIVE" -> observedOn.isBefore(horizonEnd) ? Label.STILL_OPEN : Label.NO_DEFAULT;
            default -> throw new IllegalArgumentException("Unknown outcome " + outcome);
        };
    }

    /** One decile of the resolved loans by predicted probability, lowest first. */
    record Decile(int decile, int loans, double predicted, double realized) {
    }

    /**
     * @param predictedOverRealized mean predicted over the realized rate (null with no default among the resolved loans)
     * @param auc                   ranking quality of the stored scores against the outcomes (null with too few of either class)
     * @param deciles               empty with too few resolved loans
     * @param note                  what the report cannot say, when it cannot
     */
    record Result(int resolved, int defaults, double meanPredicted, double realizedRate, Double predictedOverRealized, Double auc,
                  List<Decile> deciles, String note) {
    }

    /** @param predicted the stored probabilities of the resolved loans; @param defaulted 1 for a default within the horizon, else 0 */
    static Result of(double[] predicted, int[] defaulted) {
        int n = predicted.length;
        if (n == 0) {
            return new Result(0, 0, 0, 0, null, null, List.of(), "No stored score has a resolved outcome yet.");
        }
        double sumPredicted = 0;
        int defaults = 0;
        for (int i = 0; i < n; i++) {
            sumPredicted += predicted[i];
            defaults += defaulted[i];
        }
        double meanPredicted = sumPredicted / n;
        double realizedRate = (double) defaults / n;
        Double ratio = defaults > 0 ? meanPredicted / realizedRate : null;

        Integer[] byPredicted = new Integer[n];
        for (int i = 0; i < n; i++) {
            byPredicted[i] = i;
        }
        Arrays.sort(byPredicted, Comparator.comparingDouble(i -> predicted[i]));

        List<String> notes = new ArrayList<>();
        Double auc = null;
        int clean = n - defaults;
        if (defaults >= MIN_EACH_CLASS_FOR_AUC && clean >= MIN_EACH_CLASS_FOR_AUC) {
            auc = auc(predicted, defaulted, byPredicted, defaults, clean);
        } else {
            notes.add("Fewer than " + MIN_EACH_CLASS_FOR_AUC + " defaults or non-defaults among the resolved loans: no AUC, and the ratio is not reliable.");
        }
        List<Decile> deciles = new ArrayList<>();
        if (n >= MIN_FOR_DECILES) {
            int start = 0;
            for (int d = 0; d < 10; d++) {
                int end = (int) Math.round((d + 1) * n / 10.0);
                double p = 0;
                int y = 0;
                for (int k = start; k < end; k++) {
                    p += predicted[byPredicted[k]];
                    y += defaulted[byPredicted[k]];
                }
                int size = end - start;
                deciles.add(new Decile(d + 1, size, p / size, (double) y / size));
                start = end;
            }
        } else {
            notes.add("Fewer than " + MIN_FOR_DECILES + " resolved loans: no deciles.");
        }
        return new Result(n, defaults, meanPredicted, realizedRate, ratio, auc, deciles, notes.isEmpty() ? null : String.join(" ", notes));
    }

    /** Area under the ROC curve from average ranks, so ties in the probabilities count half. */
    private static double auc(double[] predicted, int[] defaulted, Integer[] ascending, int positives, int negatives) {
        int n = predicted.length;
        double rankSumOfDefaults = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && predicted[ascending[j + 1]] == predicted[ascending[i]]) {
                j++;
            }
            double averageRank = (i + 1 + j + 1) / 2.0;
            for (int k = i; k <= j; k++) {
                if (defaulted[ascending[k]] == 1) {
                    rankSumOfDefaults += averageRank;
                }
            }
            i = j + 1;
        }
        return (rankSumOfDefaults - positives * (positives + 1.0) / 2.0) / ((double) positives * negatives);
    }
}
