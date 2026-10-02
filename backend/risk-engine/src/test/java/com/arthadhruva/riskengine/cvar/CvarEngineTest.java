package com.arthadhruva.riskengine.cvar;

import com.arthadhruva.riskengine.ml.Normal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CvarEngineTest {

    private static final ExecutorService ONE = Executors.newFixedThreadPool(1);
    private static final ExecutorService FOUR = Executors.newFixedThreadPool(4);

    @AfterAll
    static void shutdown() {
        ONE.shutdownNow();
        FOUR.shutdownNow();
    }

    private static CvarEngine engine(ExecutorService workers) {
        return new CvarEngine(workers, 2, 400_000_000L, 60);
    }

    private static List<LoanRiskProfile> homogeneous(int n, double pd, double lgd, double ead) {
        List<LoanRiskProfile> loans = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            loans.add(new LoanRiskProfile("L" + i, pd, lgd, ead));
        }
        return loans;
    }

    private static CvarRequest request(List<LoanRiskProfile> loans, double alpha, int scenarios, double rho, boolean tilt) {
        return new CvarRequest(loans, alpha, scenarios, rho, 42L, tilt, true);
    }

    private static double standardError(double[] interval) {
        return (interval[1] - interval[0]) / (2 * 2.093);
    }

    /**
     * Exact distribution of the number of defaults among n identical loans under the one-factor copula:
     * P(K = k) is the binomial probability at the conditional PD, integrated over the common factor.
     */
    private static double[] exactDefaultCountDistribution(int n, double pd, double rho) {
        double c = Normal.inverseCdf(pd), a = Math.sqrt(rho), b = Math.sqrt(1 - rho);
        double[] logChoose = new double[n + 1];
        for (int k = 1; k <= n; k++) {
            logChoose[k] = logChoose[k - 1] + Math.log(n - k + 1) - Math.log(k);
        }
        double[] pmf = new double[n + 1];
        int points = 4001;
        double lo = -9, step = 18.0 / (points - 1);
        for (int j = 0; j < points; j++) {
            double z = lo + j * step;
            double mass = (j == 0 || j == points - 1 ? 1 : j % 2 == 1 ? 4 : 2) * step / 3 * Normal.pdf(z);
            double p = Normal.cdf((c - a * z) / b);
            for (int k = 0; k <= n; k++) {
                double logTerm = logChoose[k] + (k == 0 ? 0 : k * Math.log(p)) + (k == n ? 0 : (n - k) * Math.log1p(-p));
                pmf[k] += mass * Math.exp(logTerm);
            }
        }
        return pmf;
    }

    /** {VaR, ES} in units of one loan's loss, from the exact distribution. */
    private static double[] exactTail(double[] pmf, double alpha) {
        double cumulative = 0;
        int quantile = 0;
        for (int k = 0; k < pmf.length; k++) {
            cumulative += pmf[k];
            if (cumulative >= alpha) {
                quantile = k;
                break;
            }
        }
        double above = 0, weighted = 0;
        for (int k = quantile + 1; k < pmf.length; k++) {
            above += pmf[k];
            weighted += k * pmf[k];
        }
        return new double[]{quantile, (weighted + quantile * (1 - alpha - above)) / (1 - alpha)};
    }

    /** The simulation reproduces the exact loss distribution of a portfolio small enough to compute it. */
    @Test
    void matchesTheExactDistributionOfAHomogeneousPortfolio() {
        int n = 100;
        double pd = 0.05, rho = 0.2, unit = 0.4 * 1000;
        double[] pmf = exactDefaultCountDistribution(n, pd, rho);
        assertEquals(1.0, java.util.Arrays.stream(pmf).sum(), 1e-9);
        for (double alpha : new double[]{0.95, 0.99, 0.999}) {
            double[] exact = exactTail(pmf, alpha);
            for (boolean tilt : new boolean[]{true, false}) {
                CvarResult r = engine(FOUR).simulate(request(homogeneous(n, pd, 0.4, 1000), alpha, 100_000, rho, tilt));
                String at = "alpha " + alpha + " tilt " + tilt;
                assertEquals(exact[0] * unit, r.valueAtRisk(), unit + 1e-9, at + ": VaR within one default of the exact quantile");
                double tolerance = Math.max(5 * standardError(r.conditionalValueAtRiskConfidenceInterval()), 0.01 * exact[1] * unit);
                assertEquals(exact[1] * unit, r.conditionalValueAtRisk(), tolerance, at + ": expected shortfall");
                assertEquals(n * pd * unit, r.expectedLoss(), 1e-9);
                assertTrue(r.conditionalValueAtRisk() >= r.valueAtRisk());
                assertEquals(tilt, r.importanceSampling());
            }
        }
    }

    /** With no correlation the copula is the independent-defaults model: a plain binomial. */
    @Test
    void zeroCorrelationIsIndependentDefaults() {
        int n = 200;
        double pd = 0.03;
        double[] pmf = exactDefaultCountDistribution(n, pd, 0.0);
        double[] exact = exactTail(pmf, 0.99);
        CvarResult r = engine(FOUR).simulate(request(homogeneous(n, pd, 1.0, 1.0), 0.99, 100_000, 0.0, true));
        assertFalse(r.importanceSampling(), "the factor explains nothing, so tilting it would only add noise");
        assertEquals(0.0, r.systematicVarianceShare(), 0.0);
        assertEquals(exact[0], r.valueAtRisk(), 1.0);
        assertEquals(exact[1], r.conditionalValueAtRisk(), 0.02 * exact[1]);
        // Independent loans: the infinitely granular portfolio loses exactly its expectation.
        assertEquals(r.expectedLoss(), r.asrfValueAtRisk(), 1e-9);
    }

    /** A large, fine-grained portfolio converges to the closed-form (Basel IRB) quantile. */
    @Test
    void aGranularPortfolioApproachesTheAsrfLimit() {
        CvarResult r = engine(FOUR).simulate(request(homogeneous(3000, 0.02, 0.35, 250_000), 0.999, 20_000, 0.15, true));
        assertEquals(r.asrfValueAtRisk(), r.valueAtRisk(), 0.04 * r.asrfValueAtRisk());
        assertTrue(r.granularityAddOn() > -0.02 * r.asrfValueAtRisk(), "a finite portfolio is not safer than an infinite one");
        assertEquals(3000, r.effectiveLoans(), 1e-6);
        assertTrue(r.systematicVarianceShare() > 0.9);
        assertEquals(r.valueAtRisk() - r.expectedLoss(), r.unexpectedLoss(), 1e-6);
    }

    /** Tilting the factor toward the tail buys precision: far tighter intervals from the same scenarios. */
    @Test
    void importanceSamplingTightensTheTailEstimate() {
        List<LoanRiskProfile> loans = homogeneous(1000, 0.01, 0.4, 100_000);
        CvarResult tilted = engine(FOUR).simulate(request(loans, 0.999, 20_000, 0.15, true));
        CvarResult plain = engine(FOUR).simulate(request(loans, 0.999, 20_000, 0.15, false));
        double tiltedError = standardError(tilted.conditionalValueAtRiskConfidenceInterval());
        double plainError = standardError(plain.conditionalValueAtRiskConfidenceInterval());
        assertTrue(tiltedError < 0.5 * plainError, "tilted " + tiltedError + " vs plain " + plainError);
        assertTrue(tilted.factorShift() < -1.5);
        // and both estimate the same quantity
        assertEquals(plain.conditionalValueAtRisk(), tilted.conditionalValueAtRisk(), 5 * plainError + 5 * tiltedError);
    }

    @Test
    void theSameSeedReproducesTheResultOnAnyNumberOfThreads() {
        List<LoanRiskProfile> loans = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            loans.add(new LoanRiskProfile("L" + i, 0.002 + 0.0005 * (i % 40), 0.2 + 0.01 * (i % 30), 50_000 + 1_000.0 * (i % 97)));
        }
        CvarResult a = engine(ONE).simulate(request(loans, 0.99, 10_000, 0.15, true));
        CvarResult b = engine(FOUR).simulate(request(loans, 0.99, 10_000, 0.15, true));
        assertEquals(a.valueAtRisk(), b.valueAtRisk(), 0.0);
        assertEquals(a.conditionalValueAtRisk(), b.conditionalValueAtRisk(), 0.0);
        assertArrayEquals(a.conditionalValueAtRiskConfidenceInterval(), b.conditionalValueAtRiskConfidenceInterval(), 0.0);
        assertEquals(a.topContributions(), b.topContributions());
        assertEquals(a.exceedanceCurve(), b.exceedanceCurve());
        assertEquals(42L, a.seed());

        CvarResult other = engine(FOUR).simulate(new CvarRequest(loans, 0.99, 10_000, 0.15, 43L, true, true));
        assertTrue(other.conditionalValueAtRisk() != a.conditionalValueAtRisk(), "a different seed is a different simulation");
    }

    /** Euler allocation: the loans' contributions add up to the portfolio's expected shortfall. */
    @Test
    void contributionsAddUpToExpectedShortfall() {
        List<LoanRiskProfile> loans = List.of(
                new LoanRiskProfile("big", 0.02, 0.4, 5_000_000.0),
                new LoanRiskProfile("risky", 0.15, 0.5, 400_000.0),
                new LoanRiskProfile("a", 0.01, 0.3, 300_000.0),
                new LoanRiskProfile("b", 0.03, 0.3, 300_000.0),
                new LoanRiskProfile("c", 0.005, 0.2, 800_000.0),
                new LoanRiskProfile("safe", 0.0, 0.5, 900_000.0),
                new LoanRiskProfile("gone", 1.0, 0.1, 100_000.0));
        CvarResult r = engine(FOUR).simulate(request(loans, 0.99, 50_000, 0.15, true));
        double sum = r.topContributions().stream().mapToDouble(CvarResult.Contribution::contribution).sum();
        assertEquals(r.conditionalValueAtRisk(), sum, 1e-9 * r.conditionalValueAtRisk());
        assertEquals(1.0, r.topContributions().stream().mapToDouble(CvarResult.Contribution::share).sum(), 1e-9);
        assertEquals("big", r.topContributions().get(0).loanId(), "one name dominating the book dominates the tail");
        assertTrue(r.topContributions().stream().noneMatch(c -> c.loanId().equals("safe")), "a loan that cannot default contributes nothing");
        // the loan certain to default loses its 10,000 in every scenario, tail or not
        assertEquals(10_000.0, r.topContributions().stream().filter(c -> c.loanId().equals("gone")).findFirst().orElseThrow().contribution(), 1e-6);
        assertTrue(r.effectiveLoans() < 3, "exposure is concentrated in one loan");
        assertTrue(r.granularityAddOn() > 0);
    }

    @Test
    void correlationFattensTheTail() {
        List<LoanRiskProfile> loans = homogeneous(500, 0.02, 0.4, 100_000);
        double previous = 0;
        for (double rho : new double[]{0.0, 0.05, 0.15, 0.3}) {
            CvarResult r = engine(FOUR).simulate(request(loans, 0.999, 20_000, rho, true));
            assertTrue(r.valueAtRisk() > previous, "rho " + rho);
            previous = r.valueAtRisk();
            assertEquals(500 * 0.02 * 0.4 * 100_000, r.expectedLoss(), 1e-6, "correlation moves the tail, not the mean");
        }
    }

    @Test
    void theExceedanceCurveFallsFromTheTop() {
        CvarResult r = engine(FOUR).simulate(request(homogeneous(400, 0.02, 0.4, 100_000), 0.99, 20_000, 0.15, false));
        assertFalse(r.exceedanceCurve().isEmpty());
        double previous = 1.0;
        for (CvarResult.ExceedancePoint point : r.exceedanceCurve()) {
            assertTrue(point.probability() <= previous + 1e-12);
            previous = point.probability();
        }
        assertEquals(0.0, r.exceedanceCurve().get(0).loss(), 0.0);
    }

    /**
     * A seed the engine picks itself has to come back from a JSON client unchanged, or "rerun with the
     * reported seed" would silently run something else: a browser reads numbers as doubles, which hold
     * integers exactly only up to 2^53.
     */
    @Test
    void aSeedTheEnginePicksSurvivesAJsonRoundTripAndReproducesTheRun() {
        List<LoanRiskProfile> loans = homogeneous(60, 0.03, 0.4, 100_000);
        for (int i = 0; i < 25; i++) {
            long seed = engine(FOUR).simulate(new CvarRequest(loans, 0.99, 2_000, 0.15, null, true, false)).seed();
            assertTrue(seed >= 0 && seed < (1L << 53), "seed " + seed);
            assertEquals(seed, (long) (double) seed);
        }
        CvarResult first = engine(FOUR).simulate(new CvarRequest(loans, 0.99, 4_000, 0.15, null, true, true));
        CvarResult replay = engine(ONE).simulate(new CvarRequest(loans, 0.99, 4_000, 0.15, (long) (double) first.seed(), true, true));
        assertEquals(first.valueAtRisk(), replay.valueAtRisk(), 0.0);
        assertEquals(first.conditionalValueAtRisk(), replay.conditionalValueAtRisk(), 0.0);
        assertEquals(first.topContributions(), replay.topContributions());
    }

    @Test
    void refusesWorkBeyondTheBudget() {
        CvarEngine small = new CvarEngine(ONE, 1, 1_000_000L, 30);
        assertThrows(CvarEngine.TooLargeException.class,
                () -> small.simulate(request(homogeneous(1000, 0.01, 0.4, 1.0), 0.99, 5_000, 0.15, true)));
        small.simulate(request(homogeneous(100, 0.01, 0.4, 1.0), 0.99, 5_000, 0.15, true));
    }
}
