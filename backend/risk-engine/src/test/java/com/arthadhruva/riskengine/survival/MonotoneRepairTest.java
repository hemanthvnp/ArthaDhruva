package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repair that makes each month's transition rows stochastically ordered by source state, and the guarantee it exists
 * for: with it, the probability of having defaulted by any month does not fall as the starting state worsens, whatever the
 * raw probabilities were.
 */
class MonotoneRepairTest {

    private static final int S = DelinquencyModel.STATES;
    private static final int C = DelinquencyModel.CLASSES;
    private static final int[] BEST_TO_WORST = {DelinquencyModel.PREPAID, DelinquencyModel.CURRENT, DelinquencyModel.D30,
            DelinquencyModel.D60, DelinquencyModel.DEFAULT};

    /** Valid but deliberately disordered probabilities: each row random, with no relation between source states. */
    private static double[] random(int groups, long seed) {
        Random random = new Random(seed);
        double[] p = new double[groups * S * C];
        for (int row = 0; row < groups * S; row++) {
            double sum = 0;
            for (int c = 0; c < C; c++) {
                p[row * C + c] = Math.pow(random.nextDouble(), 3) + 1e-4;
                sum += p[row * C + c];
            }
            for (int c = 0; c < C; c++) {
                p[row * C + c] /= sum;
            }
        }
        return p;
    }

    private static double tail(double[] p, int row, int from) {
        double t = 0;
        for (int k = from; k < C; k++) {
            t += p[row * C + BEST_TO_WORST[k]];
        }
        return t;
    }

    @Test
    void producesValidProbabilitiesWhoseTailsAreOrderedBySourceState() {
        double[] p = random(200, 3);
        DelinquencyModel.monotoneRepair(p, 200 * S);
        for (int g = 0; g < 200; g++) {
            for (int s = 0; s < S; s++) {
                double sum = 0;
                for (int c = 0; c < C; c++) {
                    double v = p[(g * S + s) * C + c];
                    assertTrue(v >= -1e-15, "probability " + v);
                    sum += v;
                }
                assertEquals(1.0, sum, 1e-12);
                if (s > 0) {
                    for (int k = 1; k < C; k++) {
                        assertTrue(tail(p, g * S + s, k) >= tail(p, g * S + s - 1, k) - 1e-12, "group " + g + " class " + k);
                    }
                }
            }
        }
    }

    @Test
    void leavesAnAlreadyOrderedMonthUnchangedAndIsIdempotent() {
        double[] ordered = {
                // current:   current 30    60    default prepaid
                0.90, 0.05, 0.00, 0.01, 0.04,
                // 30 days:
                0.60, 0.20, 0.10, 0.08, 0.02,
                // 60 days:
                0.20, 0.20, 0.20, 0.38, 0.02};
        double[] copy = ordered.clone();
        DelinquencyModel.monotoneRepair(copy, S);
        assertArrayEquals(ordered, copy, 1e-15);
        double[] disordered = random(50, 8);
        DelinquencyModel.monotoneRepair(disordered, 50 * S);
        double[] once = disordered.clone();
        DelinquencyModel.monotoneRepair(disordered, 50 * S);
        assertArrayEquals(once, disordered, 1e-12);
    }

    @Test
    void aWorseStateIsNeverGivenALowerChanceOfDefaultThanABetterOne() {
        // the case that failed the gate: 30 days late judged safer than current. After the repair 30 days is at least as risky.
        double[] p = {
                0.80, 0.10, 0.01, 0.07, 0.02,
                0.85, 0.10, 0.01, 0.03, 0.01,
                0.40, 0.20, 0.20, 0.19, 0.01};
        DelinquencyModel.monotoneRepair(p, S);
        assertTrue(p[S * 0 + DelinquencyModel.DEFAULT] <= p[C + DelinquencyModel.DEFAULT] + 1e-15);
        assertTrue(p[C + DelinquencyModel.DEFAULT] <= p[2 * C + DelinquencyModel.DEFAULT] + 1e-15);
        assertEquals(0.07, p[C + DelinquencyModel.DEFAULT], 1e-15, "raised to the better state's chance of default");
    }

    /** The point of it: default probability by any month is non-decreasing in the starting state, on every regime path. */
    @Test
    void lifetimeDefaultIsMonotoneInTheStartingStateWhateverTheRawProbabilities() {
        double[][] chain = {{0.95, 0.05}, {0.10, 0.90}};
        boolean[] forced = new boolean[120];
        for (int i = 0; i < 6; i++) {
            forced[i] = true;
        }
        int[] offset = new int[forced.length];
        int rows = 0;
        for (int i = 0; i < forced.length; i++) {
            offset[i] = rows;
            rows += forced[i] ? 1 : 2;
        }
        for (long seed = 0; seed < 25; seed++) {
            double[] p = random(rows, seed);
            DelinquencyModel.monotoneRepair(p, rows * S);
            double[] cumulative = new double[S];
            double[][] byMonth = new double[S][];
            for (int start = 0; start < S; start++) {
                StateRecursion.Result r = StateRecursion.run(chain, new double[]{0.7, 0.3}, start, S, forced, offset, p, 0);
                byMonth[start] = new double[forced.length];
                double running = 0;
                for (int i = 0; i < forced.length; i++) {
                    running += r.marginalDefault()[i];
                    byMonth[start][i] = running;
                }
                cumulative[start] = running;
            }
            for (int start = 1; start < S; start++) {
                for (int i = 0; i < forced.length; i++) {
                    assertTrue(byMonth[start][i] >= byMonth[start - 1][i] - 1e-12,
                            "seed " + seed + " month " + i + ": start " + start + " " + byMonth[start][i] + " below " + byMonth[start - 1][i]);
                }
            }
            assertTrue(cumulative[0] <= cumulative[1] + 1e-12 && cumulative[1] <= cumulative[2] + 1e-12);
        }
    }
}
