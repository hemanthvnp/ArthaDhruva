package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import com.arthadhruva.riskengine.survival.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.SERVICE;

/**
 * Times a region analysis on one worker thread, the way the small container runs a portfolio, so PERFORMANCE.md
 * states a measured cost per projection. Skipped unless run with
 * {@code -Dmeasure.cost=<output file> -Dtest=PortfolioCostMeasurement}.
 */
@EnabledIfSystemProperty(named = "measure.cost", matches = ".+")
class PortfolioCostMeasurement {

    @Test
    void measure() throws Exception {
        LoanInputValidator validator = new LoanInputValidator(new MarketData());
        List<LoanFeatures> loans = new ArrayList<>();
        int[] scores = {620, 660, 700, 740, 780};
        String[] states = {"FL", "CA", "TX", "NY", "WA"};
        for (int i = 0; i < 20; i++) {
            loans.add(new LoanFeatures("C-" + i, scores[i % 5], 38.0, 250000.0, 70.0 + (i % 4) * 8, 70.0 + (i % 4) * 8, 6.0 + 0.1 * (i % 7), 360,
                    2, 1, (i % 4) >= 2 ? 25.0 : 0.0, "P", "SF", "P", "R", "N", states[i % 5], "2022-0" + (1 + i % 9)));
        }
        DecisionOptions.Resolved options = new DecisionOptions("PD_LIFETIME", 0.05, 0.0,
                new DecisionOptions.Granule("PERTURBATION", 8, null, null, null, null), null).resolve(8, 32);
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            PortfolioDecisionEngine engine = new PortfolioDecisionEngine(SERVICE, validator, one, 4000);
            engine.run(loans.subList(0, 4), Scenario.ADVERSE, options, done -> { });   // warm-up: class loading, JIT, ONNX session
            long start = System.nanoTime();
            engine.run(loans, Scenario.ADVERSE, options, done -> { });
            double seconds = (System.nanoTime() - start) / 1e9;
            int projections = loans.size() * options.members();
            String row = String.format("loans=%d variants=%d projections=%d seconds=%.2f msPerProjection=%.1f threads=1 cpus=%d%n",
                    loans.size(), options.members(), projections, seconds, seconds * 1000 / projections, Runtime.getRuntime().availableProcessors());
            Files.writeString(Path.of(System.getProperty("measure.cost")), row, StandardCharsets.UTF_8);
        } finally {
            one.shutdown();
        }
    }
}
