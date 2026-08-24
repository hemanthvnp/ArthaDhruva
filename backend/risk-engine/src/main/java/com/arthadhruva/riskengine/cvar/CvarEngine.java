package com.arthadhruva.riskengine.cvar;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Monte Carlo simulates a loan portfolio's loss distribution from independent per-loan default
 * draws, then bootstraps a confidence interval around the resulting VaR/CVaR point estimates --
 * the "genuine confidence interval, not a point score" this engine exists to provide.
 *
 * Independent-default simulation is a stated simplification: it does not model correlated /
 * systemic default risk (e.g. a shared macro shock hitting many loans at once), which would
 * widen the true tail beyond what this reports.
 */
@Service
public class CvarEngine {

    private static final int NUM_BOOTSTRAP_RESAMPLES = 500;

    public CvarResult simulate(CvarRequest request) {
        List<LoanRiskProfile> loans = request.loans();
        int numScenarios = request.numScenarios();
        double confidenceLevel = request.confidenceLevel();

        double[] scenarioLosses = simulateScenarioLosses(loans, numScenarios);

        double[] sorted = scenarioLosses.clone();
        Arrays.sort(sorted);
        double var = percentile(sorted, confidenceLevel);
        double cvar = tailMean(sorted, confidenceLevel);
        double meanLoss = Arrays.stream(scenarioLosses).average().orElse(0);

        double[] bootstrapVars = new double[NUM_BOOTSTRAP_RESAMPLES];
        double[] bootstrapCvars = new double[NUM_BOOTSTRAP_RESAMPLES];
        IntStream.range(0, NUM_BOOTSTRAP_RESAMPLES).parallel().forEach(i -> {
            double[] resample = resampleWithReplacement(scenarioLosses);
            Arrays.sort(resample);
            bootstrapVars[i] = percentile(resample, confidenceLevel);
            bootstrapCvars[i] = tailMean(resample, confidenceLevel);
        });
        Arrays.sort(bootstrapVars);
        Arrays.sort(bootstrapCvars);

        double[] varCi = {percentile(bootstrapVars, 0.025), percentile(bootstrapVars, 0.975)};
        double[] cvarCi = {percentile(bootstrapCvars, 0.025), percentile(bootstrapCvars, 0.975)};

        return new CvarResult(var, cvar, varCi, cvarCi, meanLoss, loans.size(), numScenarios, confidenceLevel);
    }

    private double[] simulateScenarioLosses(List<LoanRiskProfile> loans, int numScenarios) {
        return IntStream.range(0, numScenarios).parallel().mapToDouble(scenario -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double totalLoss = 0;
            for (LoanRiskProfile loan : loans) {
                if (random.nextDouble() < loan.pd()) {
                    totalLoss += loan.lgd() * loan.ead();
                }
            }
            return totalLoss;
        }).toArray();
    }

    private double[] resampleWithReplacement(double[] source) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double[] resample = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            resample[i] = source[random.nextInt(source.length)];
        }
        return resample;
    }

    /** {@code sortedValues} must already be sorted ascending. */
    private static double percentile(double[] sortedValues, double level) {
        int n = sortedValues.length;
        int index = Math.max(0, Math.min(n - 1, (int) Math.ceil(level * n) - 1));
        return sortedValues[index];
    }

    /** Mean of {@code sortedValues} at or beyond the {@code level} percentile (expected shortfall). */
    private static double tailMean(double[] sortedValues, double level) {
        int n = sortedValues.length;
        int index = Math.max(0, Math.min(n - 1, (int) Math.ceil(level * n) - 1));
        double sum = 0;
        for (int i = index; i < n; i++) {
            sum += sortedValues[i];
        }
        return sum / (n - index);
    }
}
