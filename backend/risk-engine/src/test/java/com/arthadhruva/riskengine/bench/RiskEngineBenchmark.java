package com.arthadhruva.riskengine.bench;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.cvar.CvarResult;
import com.arthadhruva.riskengine.cvar.LoanRiskProfile;
import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.ExplanationService;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.ModelService;
import com.arthadhruva.riskengine.score.ScoreResponse;
import com.arthadhruva.riskengine.survival.LgdPredictor;
import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The three computations a request can be expensive for, each on the real model artifacts:
 * <ul>
 *   <li>one loan's term structure to maturity (360 months, both regimes: 720 model rows), and the same
 *       loan under all five scenarios in one batched model call;</li>
 *   <li>the Shapley explanation of one score (64 antithetic orderings in one batch);</li>
 *   <li>the one-factor loss simulation of a 1,000-loan portfolio, with and without the replay pass that
 *       attributes the tail to loans.</li>
 * </ul>
 * Run {@code main}, or any single benchmark through the JMH runner.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RiskEngineBenchmark {

    private SurvivalModel survival;
    private TermStructureEngine engine;
    private ModelService pd;
    private ExplanationService explainer;
    private ExecutorService workers;
    private CvarEngine cvar;

    private TermStructureEngine.Request baseline;
    private List<TermStructureEngine.Request> everyScenario;
    private LoanFeatures loan;
    private List<LoanRiskProfile> portfolio;

    /** Threads the loss simulation may use: 1 shows the cost of the work, 4 what a small server does with it. */
    @Param({"1", "4"})
    public int threads;

    @Setup
    public void setup() throws Exception {
        MarketData market = new MarketData();
        survival = new SurvivalModel(true);
        engine = new TermStructureEngine(survival, market, new LgdPredictor(), 0.15, 2.0, 0.005);
        pd = new ModelService(market);
        explainer = new ExplanationService(pd);
        workers = Executors.newFixedThreadPool(threads);
        cvar = new CvarEngine(workers, 2, 400_000_000L, 60);

        // A new 30-year loan that differs from the reference loan in most features: the longest projection
        // and the sampled (not exact) Shapley path.
        loan = new LoanFeatures("BENCH", 640, 44.0, 380000.0, 95.0, 95.0, 7.1, 360, 1, 1, 30.0, "P", "CO", "C", "B", "Y", "FL", null);
        baseline = new TermStructureEngine.Request(loan, Scenario.BASELINE, null, null, null, null);
        everyScenario = Scenario.BUILT_IN.stream().map(s -> new TermStructureEngine.Request(loan, s, null, null, null, null)).toList();

        portfolio = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            portfolio.add(new LoanRiskProfile("L" + i, 0.004 + 0.00004 * i, 0.15 + 0.0002 * i, 150_000.0 + 250.0 * i));
        }
    }

    @TearDown
    public void tearDown() throws Exception {
        workers.shutdownNow();
        pd.close();
    }

    @Benchmark
    public TermStructureEngine.TermStructure termStructureToMaturity() {
        return engine.project(baseline, 1);
    }

    @Benchmark
    public List<TermStructureEngine.Outcome> fiveScenariosOneBatch() {
        return engine.projectAll(everyScenario);
    }

    @Benchmark
    public ExplanationService.Explanation shapleyExplanation() {
        return explainer.explain(loan);
    }

    @Benchmark
    public ScoreResponse scoreOnly() {
        return pd.score(loan);
    }

    @Benchmark
    public CvarResult lossSimulation() {
        return cvar.simulate(new CvarRequest(portfolio, 0.999, 20_000, 0.15, 42L, true, false));
    }

    @Benchmark
    public CvarResult lossSimulationWithContributions() {
        return cvar.simulate(new CvarRequest(portfolio, 0.999, 20_000, 0.15, 42L, true, true));
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder().include(RiskEngineBenchmark.class.getSimpleName()).addProfiler("gc").build()).run();
    }
}
