package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.cvar.CvarResult;
import com.arthadhruva.riskengine.cvar.LoanRiskProfile;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/**
 * Lifetime risk of a whole portfolio under several scenarios: every loan's term structure, added up into
 * expected credit loss, staging, a month-by-month run-off (balance at risk, defaults, prepayments, loss
 * emerging) and concentrations by state and by loan.
 *
 * <p><b>Cost.</b> A loan-scenario needs up to 2 x 480 model rows, so the work is cut into chunks of a few
 * loans, each chunk evaluated in one batched model call on the shared compute pool. Chunks are merged in
 * the order they were cut, not the order they finish, so a rerun reproduces every figure bit for bit
 * whatever the thread count.
 *
 * <p><b>Large portfolios.</b> Above {@code risk.portfolio.max-loans} a simple random sample of that size
 * is projected and every total scaled by N/n; the result then carries the relative standard error of the
 * lifetime ECL, so the reader sees how much the sample costs in precision.
 *
 * <p><b>Expected and unexpected loss.</b> The baseline scenario also keeps its loan-by-loan results (the
 * listing behind the totals) and feeds them to the {@link CvarEngine}: the 12-month ECL is what the
 * portfolio is expected to lose in a year, the simulated distribution says how much worse a bad year
 * can be.
 */
@Service
public class PortfolioRiskEngine {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskEngine.class);

    /** Months of the run-off series kept per scenario. */
    public static final int SERIES_MONTHS = 120;
    /** The quantile of the loss distribution stored with a run: a one-in-a-thousand year, as in Basel's IRB formula. */
    public static final double CAPITAL_CONFIDENCE = 0.999;
    public static final int CAPITAL_SCENARIOS = 20_000;

    private static final int LOANS_PER_CHUNK = 8;
    private static final int TOP_LOANS = 10;
    private static final int TOP_STATES = 12;

    private final TermStructureEngine engine;
    private final SurvivalModel model;
    private final LoanInputValidator validator;
    private final CvarEngine lossEngine;
    private final ExecutorService workers;
    private final int maxLoans;

    public PortfolioRiskEngine(TermStructureEngine engine, SurvivalModel model, LoanInputValidator validator,
                               CvarEngine lossEngine, @Qualifier("riskComputeExecutor") ExecutorService workers,
                               @Value("${risk.portfolio.max-loans:5000}") int maxLoans) {
        this.engine = engine;
        this.model = model;
        this.validator = validator;
        this.lossEngine = lossEngine;
        this.workers = workers;
        this.maxLoans = maxLoans;
    }

    /** One IFRS 9 stage's share of the portfolio. */
    public record StageBreakdown(int stage, int loans, double exposure, double ecl) {
    }

    public record StateRisk(String state, int loans, double exposure, double eclLifetime, double eclIfrs9) {
    }

    public record LoanRisk(String loanId, String state, double exposure, double pd12m, double pdLifetime,
                           double eclLifetime, int stage) {
    }

    /**
     * One loan's result under the baseline scenario.
     *
     * @param lgd    loss given default at the start of the projection
     * @param weight how many portfolio loans this one stands for: 1 unless the portfolio was sampled
     */
    public record LoanResult(String loanId, String state, double exposure, double pd12m, double pdLifetime, double lgd,
                             double ecl12m, double eclLifetime, double eclIfrs9, int stage, String stageReason,
                             double weight) {
    }

    /**
     * Month-by-month run-off, in currency.
     *
     * @param balanceAtRisk expected balance still on the book at the start of the month
     * @param defaults      balance expected to default during the month
     * @param prepayments   balance expected to prepay during the month
     * @param expectedLoss  discounted credit loss expected to arise in the month
     */
    public record Series(List<String> months, double[] balanceAtRisk, double[] defaults, double[] prepayments,
                         double[] expectedLoss) {
    }

    /**
     * @param pd12m                    exposure-weighted 12-month PD
     * @param coverage                 IFRS 9 ECL as a share of exposure
     * @param extrapolatedLoans        loans whose path leaves the model's training range, per driver and direction
     * @param eclRelativeStandardError sampling error of {@code eclLifetime}; null when every loan was projected
     * @param lossDistribution         the one-year loss distribution around the 12-month ECL (see
     *                                 {@link #lossRequest}); on the baseline scenario only, and null if the
     *                                 simulation could not run
     */
    public record ScenarioRisk(String scenario, String description, int loans, double exposure, double pd12m,
                               double pdLifetime, double ecl12m, double eclLifetime, double eclIfrs9, double eclCecl,
                               double coverage, List<StageBreakdown> stages, Series series, List<StateRisk> states,
                               List<LoanRisk> topLoans, Map<String, Integer> extrapolatedLoans,
                               Double eclRelativeStandardError, CvarResult lossDistribution) {
    }

    /**
     * @param loans         loans in the portfolio
     * @param excluded      loans that could not be projected (outside the model's domain, or matured)
     * @param projected     loans actually projected: fewer than {@code loans - excluded} when sampled
     * @param baselineLoans every projected loan's baseline result, by loan id; empty when the baseline was
     *                      not among the scenarios run
     */
    public record PortfolioRisk(String modelVersion, String forecastOrigin, int loans, int excluded, int projected,
                                List<ScenarioRisk> scenarios, List<LoanResult> baselineLoans) {
    }

    /**
     * @param seed     fixes which loans are sampled when the portfolio exceeds the limit, and the random
     *                 streams of the loss simulation
     * @param progress called with the number of loans finished so far, from the calling thread
     */
    public PortfolioRisk run(List<LoanFeatures> portfolio, List<Scenario> scenarios, long seed, IntConsumer progress) {
        // A fixed order first: the sample, and the order figures are summed in, must not depend on how
        // the caller happened to list the loans.
        List<LoanFeatures> loans = new ArrayList<>(portfolio);
        loans.sort(Comparator.comparing(LoanFeatures::loanId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (loans.size() > maxLoans) {
            Collections.shuffle(loans, new Random(seed));
            loans = loans.subList(0, maxLoans);
        }
        // Scale a sample up to the portfolio; excluded loans are assumed spread like the rest.
        double scale = loans.isEmpty() ? 1.0 : (double) portfolio.size() / loans.size();
        int baseline = baselineIndex(scenarios);

        List<Future<Chunk>> pending = new ArrayList<>();
        for (int from = 0; from < loans.size(); from += LOANS_PER_CHUNK) {
            List<LoanFeatures> slice = loans.subList(from, Math.min(loans.size(), from + LOANS_PER_CHUNK));
            pending.add(workers.submit(() -> evaluate(slice, scenarios, baseline, scale)));
        }
        Chunk total = new Chunk(scenarios.size());
        try {
            for (Future<Chunk> chunk : pending) {
                total.merge(chunk.get());
                progress.accept(total.projected + total.excluded);
            }
        } catch (InterruptedException e) {
            pending.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Portfolio run interrupted", e);
        } catch (ExecutionException e) {
            pending.forEach(f -> f.cancel(true));
            throw new IllegalStateException("Portfolio run failed", e.getCause());
        }

        boolean sampled = loans.size() < portfolio.size();
        int excluded = (int) Math.round(total.excluded * scale);
        List<String> months = new ArrayList<>(SERIES_MONTHS);
        for (int k = 1; k <= SERIES_MONTHS; k++) {
            months.add(engine.forecastOrigin().plusMonths(k).toString());
        }
        List<LoanResult> baselineLoans = new ArrayList<>(total.baselineLoans);
        baselineLoans.sort(Comparator.comparing(LoanResult::loanId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<ScenarioRisk> results = new ArrayList<>(scenarios.size());
        for (int s = 0; s < scenarios.size(); s++) {
            results.add(total.byScenario[s].result(scenarios.get(s), scale, months, sampled, portfolio.size() - excluded,
                    s == baseline ? lossDistribution(baselineLoans, seed) : null));
        }
        return new PortfolioRisk(model.version(), engine.forecastOrigin().toString(), portfolio.size(), excluded,
                total.projected, results, List.copyOf(baselineLoans));
    }

    private static int baselineIndex(List<Scenario> scenarios) {
        for (int s = 0; s < scenarios.size(); s++) {
            if (Scenario.BASELINE.name().equals(scenarios.get(s).name())) {
                return s;
            }
        }
        return -1;
    }

    /**
     * The request for the one-year loss simulation of a set of loan results. Each loan defaults with its
     * 12-month PD; what it then loses is its 12-month ECL divided by that probability. Taking the
     * severity from the ECL, rather than from LGD x balance, carries the amortization, the discounting
     * and the month-by-month LGD of the projection into the simulation, and makes the simulated mean
     * exactly the 12-month ECL: expected and unexpected loss are then statements about the same loss.
     *
     * <p>A sampled loan is simulated at {@code weight} times its size. That keeps expected loss and
     * systematic risk right and slightly overstates the name concentration of a portfolio that large,
     * which is small to begin with.
     *
     * @return null when no loan carries any default risk
     */
    public static CvarRequest lossRequest(List<LoanResult> loans, double confidenceLevel, int numScenarios,
                                          double assetCorrelation, Long seed, boolean importanceSampling) {
        List<LoanRiskProfile> profiles = new ArrayList<>(loans.size());
        for (LoanResult loan : loans) {
            if (loan.pd12m() <= 0 || loan.exposure() <= 0 || loan.ecl12m() <= 0) {
                continue;
            }
            double severity = Math.min(1.0, loan.ecl12m() / (loan.pd12m() * loan.exposure()));
            profiles.add(new LoanRiskProfile(loan.loanId(), Math.min(1.0, loan.pd12m()), severity, loan.exposure() * loan.weight()));
        }
        return profiles.isEmpty() ? null
                : new CvarRequest(profiles, confidenceLevel, numScenarios, assetCorrelation, seed, importanceSampling, true);
    }

    private CvarResult lossDistribution(List<LoanResult> loans, long seed) {
        CvarRequest request = lossRequest(loans, CAPITAL_CONFIDENCE, CAPITAL_SCENARIOS, CvarRequest.DEFAULT_ASSET_CORRELATION,
                seed, true);
        if (request == null) {
            return null;
        }
        try {
            return lossEngine.simulate(request);
        } catch (CvarEngine.BusyException | CvarEngine.TooLargeException e) {
            // The projection is the result of the run; the loss distribution can be simulated on demand.
            log.warn("Portfolio loss distribution skipped: {}", e.getMessage());
            return null;
        }
    }

    private Chunk evaluate(List<LoanFeatures> loans, List<Scenario> scenarios, int baseline, double weight) {
        Chunk chunk = new Chunk(scenarios.size());
        List<TermStructureEngine.Projection> projections = new ArrayList<>(loans.size() * scenarios.size());
        int rows = 0;
        for (LoanFeatures raw : loans) {
            int before = projections.size();
            try {
                LoanFeatures loan = raw.normalized();
                validator.check(loan);
                for (Scenario scenario : scenarios) {
                    projections.add(engine.prepare(new TermStructureEngine.Request(loan, scenario, null, null, null, null)));
                }
                for (int i = before; i < projections.size(); i++) {
                    rows += projections.get(i).rows();
                }
                chunk.projected++;
            } catch (LoanInputValidator.InvalidLoanException | TermStructureEngine.InvalidProjectionException e) {
                projections.subList(before, projections.size()).clear();
                chunk.excluded++;
            }
        }
        float[] flat = new float[rows * engine.features()];
        int[] firstRow = new int[projections.size()];
        int at = 0;
        for (int i = 0; i < projections.size(); i++) {
            firstRow[i] = at;
            projections.get(i).writeRows(flat, at);
            at += projections.get(i).rows();
        }
        double[] hazards = engine.hazards(flat, rows);
        for (int i = 0; i < projections.size(); i++) {
            TermStructureEngine.Outcome outcome = engine.finish(projections.get(i), hazards, firstRow[i]);
            int scenario = i % scenarios.size();
            chunk.byScenario[scenario].add(outcome, engine.extrapolations(outcome));
            if (scenario == baseline) {
                LoanFeatures loan = outcome.projection.request.loan();
                TermStructureEngine.Ecl ecl = outcome.ecl();
                chunk.baselineLoans.add(new LoanResult(loan.loanId(), loan.propertyState(), ecl.exposure(),
                        outcome.summary().pd12m(), outcome.summary().pdLifetime(), ecl.lgd(), ecl.ecl12m(), ecl.eclLifetime(),
                        ecl.eclIfrs9(), ecl.stage(), ecl.stageReason(), weight));
            }
        }
        return chunk;
    }

    /** What one chunk (or the merge of many) adds up to. */
    private static final class Chunk {
        final Totals[] byScenario;
        final List<LoanResult> baselineLoans = new ArrayList<>();
        int projected;
        int excluded;

        Chunk(int scenarios) {
            byScenario = new Totals[scenarios];
            for (int s = 0; s < scenarios; s++) {
                byScenario[s] = new Totals();
            }
        }

        void merge(Chunk other) {
            projected += other.projected;
            excluded += other.excluded;
            baselineLoans.addAll(other.baselineLoans);
            for (int s = 0; s < byScenario.length; s++) {
                byScenario[s].merge(other.byScenario[s]);
            }
        }
    }

    private static final class Totals {
        int loans;
        double exposure, exposurePd12, exposurePdLifetime, ecl12, eclLifetime, eclLifetimeSquares, eclIfrs9, eclCecl;
        final int[] stageLoans = new int[3];
        final double[] stageExposure = new double[3];
        final double[] stageEcl = new double[3];
        final double[] balanceAtRisk = new double[SERIES_MONTHS];
        final double[] defaults = new double[SERIES_MONTHS];
        final double[] prepayments = new double[SERIES_MONTHS];
        final double[] expectedLoss = new double[SERIES_MONTHS];
        final Map<String, double[]> states = new HashMap<>();          // loans, exposure, eclLifetime, eclIfrs9
        final Map<String, Integer> extrapolated = new TreeMap<>();
        final PriorityQueue<LoanRisk> top = new PriorityQueue<>(Comparator.comparingDouble(LoanRisk::eclLifetime));

        void add(TermStructureEngine.Outcome o, List<TermStructureEngine.Extrapolation> extrapolations) {
            TermStructureEngine.Summary summary = o.summary();
            TermStructureEngine.Ecl ecl = o.ecl();
            LoanFeatures loan = o.projection.request.loan();
            loans++;
            exposure += ecl.exposure();
            exposurePd12 += ecl.exposure() * summary.pd12m();
            exposurePdLifetime += ecl.exposure() * summary.pdLifetime();
            ecl12 += ecl.ecl12m();
            eclLifetime += ecl.eclLifetime();
            eclLifetimeSquares += ecl.eclLifetime() * ecl.eclLifetime();
            eclIfrs9 += ecl.eclIfrs9();
            eclCecl += ecl.eclCecl();
            stageLoans[ecl.stage() - 1]++;
            stageExposure[ecl.stage() - 1] += ecl.exposure();
            stageEcl[ecl.stage() - 1] += ecl.eclIfrs9();

            double[] balance = o.projection.main.exposure;
            int months = Math.min(SERIES_MONTHS, o.months());
            for (int k = 0; k < months; k++) {
                balanceAtRisk[k] += (k == 0 ? 1.0 : o.survival[k - 1]) * balance[k];
                defaults[k] += o.marginalDefault[k] * balance[k];
                prepayments[k] += o.marginalPrepay[k] * balance[k];
                expectedLoss[k] += o.discountedLoss[k];
            }
            double[] state = states.computeIfAbsent(loan.propertyState(), s -> new double[4]);
            state[0]++;
            state[1] += ecl.exposure();
            state[2] += ecl.eclLifetime();
            state[3] += ecl.eclIfrs9();
            for (TermStructureEngine.Extrapolation e : extrapolations) {
                extrapolated.merge(e.driver() + ":" + e.direction(), 1, Integer::sum);
            }
            offer(new LoanRisk(loan.loanId(), loan.propertyState(), ecl.exposure(), summary.pd12m(), summary.pdLifetime(),
                    ecl.eclLifetime(), ecl.stage()));
        }

        private void offer(LoanRisk loan) {
            top.add(loan);
            if (top.size() > TOP_LOANS) {
                top.poll();
            }
        }

        void merge(Totals other) {
            loans += other.loans;
            exposure += other.exposure;
            exposurePd12 += other.exposurePd12;
            exposurePdLifetime += other.exposurePdLifetime;
            ecl12 += other.ecl12;
            eclLifetime += other.eclLifetime;
            eclLifetimeSquares += other.eclLifetimeSquares;
            eclIfrs9 += other.eclIfrs9;
            eclCecl += other.eclCecl;
            for (int i = 0; i < 3; i++) {
                stageLoans[i] += other.stageLoans[i];
                stageExposure[i] += other.stageExposure[i];
                stageEcl[i] += other.stageEcl[i];
            }
            for (int k = 0; k < SERIES_MONTHS; k++) {
                balanceAtRisk[k] += other.balanceAtRisk[k];
                defaults[k] += other.defaults[k];
                prepayments[k] += other.prepayments[k];
                expectedLoss[k] += other.expectedLoss[k];
            }
            other.states.forEach((state, v) -> {
                double[] mine = states.computeIfAbsent(state, s -> new double[4]);
                for (int i = 0; i < 4; i++) {
                    mine[i] += v[i];
                }
            });
            other.extrapolated.forEach((key, count) -> extrapolated.merge(key, count, Integer::sum));
            other.top.forEach(this::offer);
        }

        ScenarioRisk result(Scenario scenario, double scale, List<String> months, boolean sampled, int population,
                            CvarResult lossDistribution) {
            List<StageBreakdown> stages = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                stages.add(new StageBreakdown(i + 1, scaled(stageLoans[i], scale), stageExposure[i] * scale, stageEcl[i] * scale));
            }
            List<StateRisk> byState = states.entrySet().stream()
                    .map(e -> new StateRisk(e.getKey(), scaled((int) e.getValue()[0], scale), e.getValue()[1] * scale,
                            e.getValue()[2] * scale, e.getValue()[3] * scale))
                    .sorted(Comparator.comparingDouble(StateRisk::eclLifetime).reversed().thenComparing(StateRisk::state))
                    .limit(TOP_STATES).toList();
            List<LoanRisk> topLoans = top.stream()
                    .sorted(Comparator.comparingDouble(LoanRisk::eclLifetime).reversed().thenComparing(LoanRisk::loanId,
                            Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            Map<String, Integer> extrapolatedLoans = new TreeMap<>();
            extrapolated.forEach((key, count) -> extrapolatedLoans.put(key, scaled(count, scale)));

            Double relativeError = null;
            if (sampled && loans > 1 && eclLifetime > 0) {
                double mean = eclLifetime / loans;
                double variance = Math.max(0, (eclLifetimeSquares - loans * mean * mean) / (loans - 1));
                double finitePopulation = Math.max(0, 1.0 - (double) loans / population);
                relativeError = Math.sqrt(finitePopulation * variance / loans) / mean;
            }
            return new ScenarioRisk(scenario.name(), scenario.description(), scaled(loans, scale), exposure * scale,
                    exposure > 0 ? exposurePd12 / exposure : 0, exposure > 0 ? exposurePdLifetime / exposure : 0,
                    ecl12 * scale, eclLifetime * scale, eclIfrs9 * scale, eclCecl * scale,
                    exposure > 0 ? eclIfrs9 / exposure : 0, stages,
                    new Series(months, times(balanceAtRisk, scale), times(defaults, scale), times(prepayments, scale),
                            times(expectedLoss, scale)),
                    byState, topLoans, extrapolatedLoans, relativeError, lossDistribution);
        }

        private static int scaled(int count, double scale) {
            return (int) Math.round(count * scale);
        }

        private static double[] times(double[] values, double scale) {
            double[] out = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                out[i] = values[i] * scale;
            }
            return out;
        }
    }
}
