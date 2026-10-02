package com.arthadhruva.riskengine.cvar;

import com.arthadhruva.riskengine.ml.Normal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * The loss distribution of a loan portfolio, and from it Value-at-Risk, expected shortfall (CVaR) and
 * each loan's contribution to the tail.
 *
 * <p><b>Model.</b> A one-factor Gaussian copula, the model behind the Basel IRB capital formula: loan i
 * defaults when {@code sqrt(rho) Z + sqrt(1 - rho) e_i < inverseNormal(PD_i)}, where Z is the state of the
 * economy shared by every loan and e_i is the loan's own luck. Given Z, loans default independently with
 * probability {@code p_i(Z) = N((inverseNormal(PD_i) - sqrt(rho) Z) / sqrt(1 - rho))}. With {@code rho = 0}
 * this is the independent-defaults model; with {@code rho > 0} bad years hit many loans at once, which is
 * where portfolio tail risk comes from.
 *
 * <p><b>Importance sampling.</b> A 99.9% quantile is decided by one scenario in a thousand, so plain
 * Monte Carlo wastes almost every draw. Z is instead drawn from N(mu, 1), centred on the bad years, and
 * each scenario carries the likelihood ratio {@code exp(-mu Z + mu^2 / 2)} that undoes the tilt. Any mu
 * gives an unbiased estimate; this one is the factor level at the target quantile in the large-portfolio
 * limit, shrunk by the share of loss variance the factor explains (so it fades to no tilt as rho goes to
 * zero, where the factor explains nothing).
 *
 * <p><b>Estimators.</b> Tail probability, VaR and expected shortfall are weighted sums over the sorted
 * scenarios. Standard errors come from sectioning: the scenarios are split into {@value #SECTIONS}
 * independent sections, each estimated on its own, and the spread of those estimates gives the interval
 * -- no resampling, and valid for the weighted estimator. Contributions are the Euler allocation of
 * expected shortfall, {@code E[loss_i | portfolio loss in the tail]}; they add up to it exactly.
 *
 * <p><b>Benchmark.</b> Beside the simulated VaR the engine reports the closed-form VaR of an infinitely
 * fine-grained portfolio (the ASRF limit). The gap between the two is what the portfolio's concentration
 * in individual names adds.
 *
 * <p><b>Engineering.</b> Loans are flattened into primitive arrays; scenarios are simulated in blocks on
 * the shared compute pool. Scenario s always draws from the same random stream (seed + s), so a result
 * is identical on any number of threads and can be reproduced from the seed it reports. Tail scenarios
 * are re-simulated from their seeds to attribute losses to loans, instead of storing every default.
 * Concurrent simulations are capped, and a request larger than the work budget is refused up front.
 */
@Service
public class CvarEngine {

    static final int SECTIONS = 20;
    /** Two-sided 95% Student t quantile for SECTIONS - 1 degrees of freedom. */
    private static final double T_95 = 2.093;
    private static final int BLOCK = 256;
    private static final int QUADRATURE_POINTS = 161;
    private static final double QUADRATURE_RANGE = 8.0;
    private static final long STREAM_GAMMA = 0x9E3779B97F4A7C15L;
    /** A seed the engine picks itself stays below 2^53: a JSON client that reads numbers as doubles (every
     * browser) must get back exactly the seed that reproduces the run, not a rounded neighbour. */
    private static final long GENERATED_SEED_BOUND = 1L << 53;
    private static final int TOP_CONTRIBUTORS = 10;
    private static final int CURVE_POINTS = 24;

    /** The simulation would exceed the work budget (loans x scenarios). */
    public static class TooLargeException extends RuntimeException {
        TooLargeException(String message) {
            super(message);
        }
    }

    /** No simulation slot became free in time, or the simulation ran past its deadline. */
    public static class BusyException extends RuntimeException {
        BusyException(String message) {
            super(message);
        }
    }

    private final ExecutorService workers;
    private final Semaphore slots;
    private final long maxWork;
    private final long timeoutMillis;

    public CvarEngine(@Qualifier("riskComputeExecutor") ExecutorService workers,
                      @Value("${cvar.max-concurrent:2}") int maxConcurrent,
                      @Value("${cvar.max-work:400000000}") long maxWork,
                      @Value("${cvar.timeout-seconds:30}") long timeoutSeconds) {
        this.workers = workers;
        this.slots = new Semaphore(maxConcurrent);
        this.maxWork = maxWork;
        this.timeoutMillis = timeoutSeconds * 1000;
    }

    public CvarResult simulate(CvarRequest request) {
        long work = (long) request.loans().size() * request.numScenarios();
        if (work > maxWork) {
            throw new TooLargeException("This portfolio of " + request.loans().size() + " loans can be simulated with at most "
                    + maxWork / request.loans().size() + " scenarios");
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("Interrupted while waiting for a simulation slot");
        }
        if (!acquired) {
            throw new BusyException("Too many simulations are running; try again shortly");
        }
        try {
            return run(request);
        } finally {
            slots.release();
        }
    }

    private CvarResult run(CvarRequest request) {
        long started = System.nanoTime();
        long deadline = started + timeoutMillis * 1_000_000L;
        List<LoanRiskProfile> loans = request.loans();
        int n = loans.size();
        int scenarios = request.numScenarios() - request.numScenarios() % SECTIONS;  // whole sections
        double alpha = request.confidenceLevel();
        double rho = request.assetCorrelation();
        Long requestedSeed = request.seed();
        long seed = requestedSeed != null ? requestedSeed : new SplittableRandom().nextLong(GENERATED_SEED_BOUND);

        // Flattened portfolio: loss if the loan defaults, and its default threshold in units of the
        // idiosyncratic shock: default <=> e_i < threshold_i - factorLoading * Z.
        double[] lossGivenDefault = new double[n];
        double[] threshold = new double[n];
        double idiosyncratic = Math.sqrt(1 - rho);
        double factorLoading = Math.sqrt(rho / (1 - rho));
        double expectedLoss = 0, exposure = 0, squaredExposure = 0;
        for (int i = 0; i < n; i++) {
            LoanRiskProfile loan = loans.get(i);
            lossGivenDefault[i] = loan.lgd() * loan.ead();
            threshold[i] = Normal.inverseCdf(loan.pd()) / idiosyncratic;
            expectedLoss += loan.pd() * lossGivenDefault[i];
            exposure += loan.ead();
            squaredExposure += loan.ead() * loan.ead();
        }
        double asrfVar = 0;
        double quantileShift = factorLoading * Normal.inverseCdf(alpha);
        for (int i = 0; i < n; i++) {
            asrfVar += lossGivenDefault[i] * Normal.cdf(threshold[i] + quantileShift);
        }
        double systematicShare = systematicVarianceShare(lossGivenDefault, threshold, factorLoading);
        double mu = request.importanceSampling() ? -Normal.inverseCdf(alpha) * systematicShare : 0.0;

        // Pass 1: portfolio loss and likelihood ratio of every scenario.
        double[] losses = new double[scenarios];
        double[] weights = new double[scenarios];
        forEachBlock(scenarios, deadline, (from, to) -> {
            for (int s = from; s < to; s++) {
                SplittableRandom random = new SplittableRandom(seed + s * STREAM_GAMMA);
                double z = mu + random.nextGaussian();
                double shift = factorLoading * z;
                double loss = 0;
                for (int i = 0; i < n; i++) {
                    if (random.nextGaussian() < threshold[i] - shift) {
                        loss += lossGivenDefault[i];
                    }
                }
                losses[s] = loss;
                weights[s] = Math.exp(-mu * z + mu * mu / 2);
            }
        });

        Tail tail = tail(losses, weights, 0, scenarios, alpha);
        double[] sectionVar = new double[SECTIONS];
        double[] sectionEs = new double[SECTIONS];
        int perSection = scenarios / SECTIONS;
        for (int b = 0; b < SECTIONS; b++) {
            Tail section = tail(losses, weights, b * perSection, (b + 1) * perSection, alpha);
            sectionVar[b] = section.valueAtRisk;
            sectionEs[b] = section.expectedShortfall;
        }
        double varError = standardError(sectionVar), esError = standardError(sectionEs);

        // Pass 2: replay the tail scenarios from their seeds and attribute each loss to its loans.
        double[] contribution = new double[n];
        if (request.contributions() && tail.expectedShortfall > 0) {
            double[] tailWeight = new double[scenarios];   // weight of each scenario in the expected shortfall
            for (int j = 0; j < tail.count; j++) {
                tailWeight[tail.order[j]] = tail.share[j];
            }
            double[][] perBlock = new double[(scenarios + BLOCK - 1) / BLOCK][];
            forEachBlock(scenarios, deadline, (from, to) -> {
                double[] local = null;
                for (int s = from; s < to; s++) {
                    if (tailWeight[s] == 0) {
                        continue;
                    }
                    if (local == null) {
                        local = new double[n];
                    }
                    SplittableRandom random = new SplittableRandom(seed + s * STREAM_GAMMA);
                    double shift = factorLoading * (mu + random.nextGaussian());
                    for (int i = 0; i < n; i++) {
                        if (random.nextGaussian() < threshold[i] - shift) {
                            local[i] += tailWeight[s] * lossGivenDefault[i];
                        }
                    }
                }
                perBlock[from / BLOCK] = local;
            });
            for (double[] local : perBlock) {   // merged in block order: the same sum on any thread count
                if (local != null) {
                    for (int i = 0; i < n; i++) {
                        contribution[i] += local[i];
                    }
                }
            }
        }
        List<CvarResult.Contribution> top = IntStream.range(0, n).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> contribution[i]).reversed().thenComparingInt(i -> i))
                .limit(request.contributions() ? TOP_CONTRIBUTORS : 0)
                .filter(i -> contribution[i] > 0)
                .map(i -> new CvarResult.Contribution(loans.get(i).loanId(), i, contribution[i],
                        contribution[i] / tail.expectedShortfall, loans.get(i).pd() * lossGivenDefault[i]))
                .toList();

        return new CvarResult(tail.valueAtRisk, tail.expectedShortfall,
                new double[]{Math.max(0, tail.valueAtRisk - T_95 * varError), tail.valueAtRisk + T_95 * varError},
                new double[]{Math.max(0, tail.expectedShortfall - T_95 * esError), tail.expectedShortfall + T_95 * esError},
                expectedLoss, Math.max(0, tail.valueAtRisk - expectedLoss), asrfVar, tail.valueAtRisk - asrfVar, exposure,
                squaredExposure > 0 ? exposure * exposure / squaredExposure : 0, n, scenarios, alpha, rho,
                mu != 0, mu, systematicShare, seed, top, exceedanceCurve(losses, weights),
                (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * The share of the variance of portfolio loss that the common factor explains,
     * {@code Var(E[L | Z]) / Var(L)}, by quadrature over Z.
     */
    static double systematicVarianceShare(double[] lossGivenDefault, double[] threshold, double factorLoading) {
        if (factorLoading == 0) {
            return 0;
        }
        double step = 2 * QUADRATURE_RANGE / (QUADRATURE_POINTS - 1);
        double mean = 0, meanSquare = 0, idiosyncraticVariance = 0, total = 0;
        for (int k = 0; k < QUADRATURE_POINTS; k++) {
            double z = -QUADRATURE_RANGE + k * step;
            double simpson = k == 0 || k == QUADRATURE_POINTS - 1 ? 1 : k % 2 == 1 ? 4 : 2;
            double mass = simpson * step / 3 * Normal.pdf(z);
            double conditionalMean = 0, conditionalVariance = 0;
            for (int i = 0; i < lossGivenDefault.length; i++) {
                double p = Normal.cdf(threshold[i] - factorLoading * z);
                conditionalMean += lossGivenDefault[i] * p;
                conditionalVariance += lossGivenDefault[i] * lossGivenDefault[i] * p * (1 - p);
            }
            total += mass;
            mean += mass * conditionalMean;
            meanSquare += mass * conditionalMean * conditionalMean;
            idiosyncraticVariance += mass * conditionalVariance;
        }
        mean /= total;
        double systematicVariance = Math.max(0, meanSquare / total - mean * mean);
        double variance = systematicVariance + idiosyncraticVariance / total;
        return variance > 0 ? systematicVariance / variance : 0;
    }

    /** The upper tail of the weighted loss distribution over scenarios {@code [from, to)}. */
    private static final class Tail {
        double valueAtRisk;
        double expectedShortfall;
        int[] order;      // scenario indices, largest loss first, up to and including the VaR scenario
        double[] share;   // each one's weight in the expected shortfall (they sum to 1)
        int count;
    }

    /**
     * With scenarios sorted by loss, largest first, and tail mass accumulated as {@code sum w / S}: VaR is
     * the loss at which the mass reaches {@code 1 - alpha}, and expected shortfall is the weighted mean of
     * the losses above it, with the VaR scenario taking whatever mass is left to make up {@code 1 - alpha}.
     */
    private static Tail tail(double[] losses, double[] weights, int from, int to, double alpha) {
        int size = to - from;
        Integer[] sorted = new Integer[size];
        for (int j = 0; j < size; j++) {
            sorted[j] = from + j;
        }
        Arrays.sort(sorted, Comparator.comparingDouble((Integer s) -> losses[s]).reversed().thenComparingInt(s -> s));
        double target = 1 - alpha, mass = 0, weightedLoss = 0;
        Tail tail = new Tail();
        tail.order = new int[size];
        tail.share = new double[size];
        for (int j = 0; j < size; j++) {
            int s = sorted[j];
            double w = weights[s] / size;
            tail.order[j] = s;
            if (mass + w >= target || j == size - 1) {
                double remainder = Math.max(0, target - mass);   // the part of this scenario inside the tail
                tail.valueAtRisk = losses[s];
                tail.expectedShortfall = (weightedLoss + remainder * losses[s]) / target;
                tail.share[j] = remainder / target;
                tail.count = j + 1;
                break;
            }
            mass += w;
            weightedLoss += w * losses[s];
            tail.share[j] = w / target;
        }
        return tail;
    }

    private static double standardError(double[] sectionEstimates) {
        double mean = Arrays.stream(sectionEstimates).average().orElse(0);
        double sumSquares = 0;
        for (double v : sectionEstimates) {
            sumSquares += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSquares / (sectionEstimates.length - 1) / sectionEstimates.length);
    }

    /** P(loss > x) at evenly spaced loss levels from zero to the largest simulated loss. */
    private static List<CvarResult.ExceedancePoint> exceedanceCurve(double[] losses, double[] weights) {
        double max = Arrays.stream(losses).max().orElse(0);
        if (max <= 0) {
            return List.of();
        }
        double[] above = new double[CURVE_POINTS];
        for (int s = 0; s < losses.length; s++) {
            // scenario s exceeds every level strictly below its loss
            int levels = (int) Math.min(CURVE_POINTS, Math.ceil(losses[s] / max * CURVE_POINTS));
            for (int k = 0; k < levels; k++) {
                above[k] += weights[s];
            }
        }
        List<CvarResult.ExceedancePoint> curve = new ArrayList<>(CURVE_POINTS);
        for (int k = 0; k < CURVE_POINTS; k++) {
            curve.add(new CvarResult.ExceedancePoint(max * k / CURVE_POINTS, Math.min(1.0, above[k] / losses.length)));
        }
        return curve;
    }

    private interface BlockTask {
        void run(int from, int to);
    }

    private void forEachBlock(int scenarios, long deadline, BlockTask task) {
        List<Future<?>> pending = new ArrayList<>();
        for (int from = 0; from < scenarios; from += BLOCK) {
            int start = from, end = Math.min(scenarios, from + BLOCK);
            pending.add(workers.submit(() -> {
                if (System.nanoTime() > deadline) {
                    throw new BusyException("The simulation ran past its time limit; use fewer scenarios");
                }
                task.run(start, end);
            }));
        }
        try {
            for (Future<?> future : pending) {
                future.get();
            }
        } catch (InterruptedException e) {
            pending.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new BusyException("The simulation was interrupted");
        } catch (ExecutionException e) {
            pending.forEach(f -> f.cancel(true));
            if (e.getCause() instanceof BusyException busy) {
                throw busy;
            }
            throw new IllegalStateException("The simulation failed", e.getCause());
        }
    }
}
