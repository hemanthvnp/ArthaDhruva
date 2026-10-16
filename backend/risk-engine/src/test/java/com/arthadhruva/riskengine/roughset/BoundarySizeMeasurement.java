package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.ENGINE;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.prime;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.request;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.weak;

/**
 * Measures how wide the spread of a stressed figure is as a function of the perturbation bands, so the documented
 * defaults rest on a measurement. Skipped unless run with
 * {@code -Dmeasure.boundary=<output file> -Dtest=BoundarySizeMeasurement}; each row is appended as it is computed.
 * {@code -Dmeasure.loans=weak} restricts it to one of the two loans.
 */
@EnabledIfSystemProperty(named = "measure.boundary", matches = ".+")
class BoundarySizeMeasurement {

    private static final int MEMBERS = 32;
    private static final long SEED = 17L;

    @Test
    void measure() throws IOException {
        Path out = Path.of(System.getProperty("measure.boundary"));
        Files.writeString(out, "loan scenario metric shockBand timingBand base min max relHalfWidth\n", StandardCharsets.UTF_8);
        String only = System.getProperty("measure.loans", "prime,weak");
        List<LoanFeatures> loans = List.of(prime(), weak()).stream().filter(l -> only.contains(l.loanId())).toList();
        for (LoanFeatures loan : loans) {
            for (Scenario scenario : List.of(Scenario.ADVERSE, Scenario.SEVERELY_ADVERSE)) {
                for (double band : new double[]{0.1, 0.2, 0.3}) {
                    for (int timing : new int[]{0, 3, 6}) {
                        DecisionOptions.Resolved o = new DecisionOptions("PD_LIFETIME", 0.5, 0.0,
                                new DecisionOptions.Granule("PERTURBATION", MEMBERS, band, timing, null, null), SEED).resolve(MEMBERS, 128);
                        List<TermStructureEngine.Outcome> outcomes = ENGINE.projectAll(GranuleBuilder.build(scenario, o, SEED).stream()
                                .map(s -> request(loan, s)).toList());
                        for (RoughSetMetric metric : List.of(RoughSetMetric.PD_12M, RoughSetMetric.PD_LIFETIME)) {
                            double[] values = outcomes.stream().mapToDouble(metric::of).toArray();
                            RegionClassifier.Classification c = RegionClassifier.classify(values, 0.5, 0.0);
                            String row = String.format("%s %s %s %.2f %d %.5f %.5f %.5f %.3f%n", loan.loanId(), scenario.name(), metric, band, timing,
                                    values[0], c.min(), c.max(), values[0] > 0 ? c.width() / (2 * values[0]) : 0.0);
                            Files.writeString(out, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                        }
                    }
                }
            }
        }
    }
}
