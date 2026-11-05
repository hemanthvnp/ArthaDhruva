package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recursion over (regime, state) against the definition it computes: the expectation over every regime path of the
 * state recursion applied within that path. A short horizon is enumerated path by path (2^T of them), so the two
 * agree to rounding, not to a tolerance of convenience.
 */
class StateRecursionTest {

    private static final int STATES = 3;
    private static final int CLASSES = STATES + 2;
    private static final double[][] CHAIN = {{0.93, 0.07}, {0.21, 0.79}};

    /** Random but valid transition probabilities: for each model row and source state, five classes summing to 1. */
    private static double[] probabilities(int rows, long seed) {
        Random random = new Random(seed);
        double[] p = new double[rows * STATES * CLASSES];
        for (int base = 0; base < p.length; base += CLASSES) {
            double sum = 0;
            for (int c = 0; c < CLASSES; c++) {
                p[base + c] = 0.01 + random.nextDouble();
                sum += p[base + c];
            }
            for (int c = 0; c < CLASSES; c++) {
                p[base + c] /= sum;
            }
        }
        return p;
    }

    /** Plan with the given forced months: a forced month has one model row (stressed), any other has two (calm, stressed). */
    private static int[] offsets(boolean[] forced) {
        int[] offset = new int[forced.length];
        int at = 0;
        for (int i = 0; i < forced.length; i++) {
            offset[i] = at;
            at += forced[i] ? 1 : 2;
        }
        return offset;
    }

    private record Brute(double[] defaults, double[] prepays, double[] survival) {
    }

    /** Every regime path, each weighted by its probability, with the state recursion applied inside it. */
    private static Brute enumerate(boolean[] forced, int[] offset, double[] p, double[] initial, int start) {
        int months = forced.length;
        double[] defaults = new double[months], prepays = new double[months], survival = new double[months];
        for (int r0 = 0; r0 < 2; r0++) {
            for (int path = 0; path < 1 << months; path++) {
                double weight = initial[r0];
                int previous = r0;
                int[] regime = new int[months];
                for (int i = 0; i < months; i++) {
                    int r = (path >> i) & 1;
                    if (forced[i]) {
                        if (r != 1) {
                            weight = 0;      // a forced month is stressed whatever the chain says
                            break;
                        }
                    } else {
                        weight *= CHAIN[previous][r];
                    }
                    regime[i] = r;
                    previous = r;
                }
                if (weight == 0) {
                    continue;
                }
                double[] v = new double[STATES];
                v[start] = 1.0;
                for (int i = 0; i < months; i++) {
                    int row = offset[i] + (forced[i] ? 0 : regime[i]);
                    double[] next = new double[STATES];
                    double d = 0, pr = 0;
                    for (int s = 0; s < STATES; s++) {
                        int base = (row * STATES + s) * CLASSES;
                        d += v[s] * p[base + STATES];
                        pr += v[s] * p[base + STATES + 1];
                        for (int s2 = 0; s2 < STATES; s2++) {
                            next[s2] += v[s] * p[base + s2];
                        }
                    }
                    defaults[i] += weight * d;
                    prepays[i] += weight * pr;
                    double alive = 0;
                    for (int s = 0; s < STATES; s++) {
                        alive += next[s];
                    }
                    survival[i] += weight * alive;
                    v = next;
                }
            }
        }
        return new Brute(defaults, prepays, survival);
    }

    @Test
    void agreesWithEnumeratingEveryRegimePath() {
        boolean[][] schedules = {
                new boolean[7],
                {true, true, false, false, false, false, false},
                {false, false, true, false, false, true, true},
                {true, true, true, true, true, true, true}};
        double[] initial = {0.7, 0.3};
        for (boolean[] forced : schedules) {
            int[] offset = offsets(forced);
            double[] p = probabilities(offset[offset.length - 1] + (forced[forced.length - 1] ? 1 : 2), 11);
            for (int start = 0; start < STATES; start++) {
                StateRecursion.Result got = StateRecursion.run(CHAIN, initial, start, STATES, forced, offset, p, 0);
                Brute want = enumerate(forced, offset, p, initial, start);
                for (int i = 0; i < forced.length; i++) {
                    String at = "start " + start + " month " + i;
                    assertEquals(want.defaults[i], got.marginalDefault()[i], 1e-12, at + " default");
                    assertEquals(want.prepays[i], got.marginalPrepay()[i], 1e-12, at + " prepay");
                    assertEquals(want.survival[i], got.survival()[i], 1e-12, at + " active");
                }
            }
        }
    }

    @Test
    void activePlusDefaultedPlusPrepaidIsOneInEveryMonth() {
        boolean[] forced = new boolean[360];
        for (int i = 0; i < 24; i++) {
            forced[i] = true;
        }
        int[] offset = offsets(forced);
        double[] p = probabilities(offset[359] + 2, 5);
        for (int start = 0; start < STATES; start++) {
            StateRecursion.Result r = StateRecursion.run(CHAIN, new double[]{0.9, 0.1}, start, STATES, forced, offset, p, 0);
            double cumulativeDefault = 0, cumulativePrepay = 0;
            for (int i = 0; i < forced.length; i++) {
                cumulativeDefault += r.marginalDefault()[i];
                cumulativePrepay += r.marginalPrepay()[i];
                assertEquals(1.0, r.survival()[i] + cumulativeDefault + cumulativePrepay, 1e-12, "start " + start + " month " + i);
            }
            assertEquals(cumulativeDefault, r.cumulativeDefault(), 1e-12);
        }
    }

    @Test
    void aSingleStateIsTheSurvivalRecursion() {
        // One state with probabilities (continue, default, prepay) is the existing model's hazards, whatever the regime chain does.
        boolean[] forced = new boolean[12];
        int[] offset = offsets(forced);
        double[] p = new double[(offset[11] + 2) * 3];
        for (int row = 0; row < p.length / 3; row++) {
            double hd = 0.002 + 0.001 * (row % 2), hp = 0.01 + 0.002 * (row % 3);
            p[3 * row] = 1 - hd - hp;
            p[3 * row + 1] = hd;
            p[3 * row + 2] = hp;
        }
        StateRecursion.Result r = StateRecursion.run(CHAIN, new double[]{1, 0}, 0, 1, forced, offset, p, 0);
        double calm = 1, stressed = 0, defaults = 0;
        for (int i = 0; i < 12; i++) {
            double inCalm = calm * CHAIN[0][0] + stressed * CHAIN[1][0];
            double inStressed = calm * CHAIN[0][1] + stressed * CHAIN[1][1];
            int row = offset[i];
            double hdCalm = p[3 * row + 1], hpCalm = p[3 * row + 2], hdStressed = p[3 * (row + 1) + 1], hpStressed = p[3 * (row + 1) + 2];
            defaults += inCalm * hdCalm + inStressed * hdStressed;
            calm = inCalm * (1 - hdCalm - hpCalm);
            stressed = inStressed * (1 - hdStressed - hpStressed);
            assertEquals(calm + stressed, r.survival()[i], 1e-12);
        }
        assertEquals(defaults, r.cumulativeDefault(), 1e-12);
        assertTrue(r.byState()[0].length == 1);
    }
}
