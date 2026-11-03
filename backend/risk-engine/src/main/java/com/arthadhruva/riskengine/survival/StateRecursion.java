package com.arthadhruva.riskengine.survival;

/**
 * The exact forward recursion over (macro regime, delinquency state), shared by the multi-state engine and the
 * uncertainty draws, and written so the brute-force test can call it with any transition probabilities.
 *
 * <p>It carries {@code a_k(r, s)}, the probability of being active at the end of month k, in regime r and in state
 * s, and for each month:
 * <pre>
 *   b_k(r', s)   = sum_r a_{k-1}(r, s) P[r][r']          (all mass on the stressed regime while a scenario forces it)
 *   default_k    = sum_{r', s} b_k(r', s) T_k(r', s -> default)        prepay_k likewise
 *   a_k(r', s')  = sum_s b_k(r', s) T_k(r', s -> s')
 * </pre>
 * That is the expectation over all 2^T regime paths, in O(T x R x S^2) and with no sampling.
 *
 * <p>Probabilities are laid out flat: for model row {@code j}, source state {@code s} and class {@code c},
 * {@code p[(j * states + s) * (states + 2) + c]}, where classes {@code 0 .. states - 1} are the next states and the
 * last two are default and prepayment. With one state this is the survival model's (continue, default, prepay), so
 * the same recursion also projects the existing model's hazards.
 */
final class StateRecursion {

    private StateRecursion() {
    }

    /** One value per projected month, from the first. {@code byState[i][s]} is the active mass in state s. */
    record Result(double[] survival, double[] marginalDefault, double[] marginalPrepay, double[] stressed,
                  double[] defaultHazard, double[] prepayHazard, double[][] byState, double cumulativeDefault,
                  double cumulativePrepay) {
    }

    /**
     * @param chain         the regime transition matrix {@code [calm, stressed][calm, stressed]}
     * @param initialRegime {@code [calm, stressed]}: where the regime is at the start of the projection
     * @param startState    the state the loan is in at the start (0 .. states - 1)
     * @param forced        per month, whether the scenario forces the stressed regime (one model row) or not (two)
     * @param rowOffset     per month, the offset of its first model row from {@code firstRow}
     * @param firstRow      the model row the plan's first row sits at in {@code p}
     */
    static Result run(double[][] chain, double[] initialRegime, int startState, int states, boolean[] forced, int[] rowOffset,
                      double[] p, int firstRow) {
        int months = forced.length;
        int classes = states + 2;
        double[][] alive = new double[2][states];
        alive[0][startState] = initialRegime[0];
        alive[1][startState] = initialRegime[1];
        double[] survival = new double[months], marginalDefault = new double[months], marginalPrepay = new double[months];
        double[] stressed = new double[months], defaultHazard = new double[months], prepayHazard = new double[months];
        double[][] byState = new double[months][states];
        double cumulativeDefault = 0, cumulativePrepay = 0;
        double[][] at = new double[2][states];
        for (int i = 0; i < months; i++) {
            int row = firstRow + rowOffset[i];
            int[] regimes;
            int[] rows;
            if (forced[i]) {
                for (int s = 0; s < states; s++) {
                    at[0][s] = 0;
                    at[1][s] = alive[0][s] + alive[1][s];
                }
                regimes = new int[]{1};
                rows = new int[]{row};
            } else {
                for (int s = 0; s < states; s++) {
                    at[0][s] = alive[0][s] * chain[0][0] + alive[1][s] * chain[1][0];
                    at[1][s] = alive[0][s] * chain[0][1] + alive[1][s] * chain[1][1];
                }
                regimes = new int[]{0, 1};
                rows = new int[]{row, row + 1};
            }
            double defaults = 0, prepays = 0, inStressed = 0, atRisk = 0;
            double[][] next = new double[2][states];
            for (int k = 0; k < regimes.length; k++) {
                int r = regimes[k];
                for (int s = 0; s < states; s++) {
                    double mass = at[r][s];
                    atRisk += mass;
                    if (r == 1) {
                        inStressed += mass;
                    }
                    int base = (rows[k] * states + s) * classes;
                    defaults += mass * p[base + states];
                    prepays += mass * p[base + states + 1];
                    for (int s2 = 0; s2 < states; s2++) {
                        next[r][s2] += mass * p[base + s2];
                    }
                }
            }
            alive = next;
            double active = 0;
            for (int s = 0; s < states; s++) {
                byState[i][s] = alive[0][s] + alive[1][s];
                active += byState[i][s];
            }
            survival[i] = active;
            marginalDefault[i] = defaults;
            marginalPrepay[i] = prepays;
            stressed[i] = atRisk > 0 ? inStressed / atRisk : 0;
            defaultHazard[i] = atRisk > 0 ? defaults / atRisk : 0;
            prepayHazard[i] = atRisk > 0 ? prepays / atRisk : 0;
            cumulativeDefault += defaults;
            cumulativePrepay += prepays;
        }
        return new Result(survival, marginalDefault, marginalPrepay, stressed, defaultHazard, prepayHazard, byState,
                cumulativeDefault, cumulativePrepay);
    }
}
