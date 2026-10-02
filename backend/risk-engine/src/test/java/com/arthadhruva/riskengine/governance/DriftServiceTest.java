package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.ModelService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftServiceTest {

    private static ModelService model;
    private static DriftService drift;
    private static List<LoanFeatures> catalog;

    @BeforeAll
    static void load() throws Exception {
        model = new ModelService(new MarketData());
        drift = new DriftService(model);
        try (InputStream is = DriftServiceTest.class.getClassLoader().getResourceAsStream("loan_catalog.json")) {
            catalog = new ObjectMapper().readValue(is.readAllBytes(), new TypeReference<List<LoanFeatures>>() {});
        }
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    private static DriftService.FeatureDrift feature(DriftService.DriftReport report, String name) {
        return report.features().stream().filter(f -> f.feature().equals(name)).findFirst().orElseThrow();
    }

    /** The demo catalog is a sample of the population the model was trained on: nothing should drift. */
    @Test
    void aSampleOfTheTrainingPopulationIsStable() {
        DriftService.DriftReport report = drift.report(catalog, List.of());
        assertEquals(400, report.loans());
        assertEquals(16, report.features().size());
        assertNull(report.score());
        for (DriftService.FeatureDrift f : report.features()) {
            assertEquals(400, f.observations());
            assertEquals(1.0, f.bins().stream().mapToDouble(DriftService.Bin::actual).sum(), 1e-9, f.feature());
            assertTrue(f.psi() >= 0, f.feature());
            if (!f.feature().equals("credit_score")) {   // the catalog is drawn evenly across the credit-score ranking
                assertTrue(f.psi() - f.noiseFloor() < 0.25, f.feature() + " psi " + f.psi() + " floor " + f.noiseFloor());
            }
        }
    }

    @Test
    void aShiftedPortfolioIsFlaggedOnTheFeaturesThatShifted() {
        List<LoanFeatures> subprime = new ArrayList<>();
        for (LoanFeatures l : catalog) {
            subprime.add(new LoanFeatures(l.loanId(), 600, l.originalDti(), l.originalUpb(), l.originalCltv(), l.originalLtv(),
                    l.originalInterestRate(), l.originalLoanTerm(), l.numberOfBorrowers(), l.numberOfUnits(), l.miPercent(),
                    l.occupancyStatus(), l.propertyType(), l.loanPurpose(), l.channel(), l.firstTimeHomebuyerFlag(), "FL",
                    l.originationMonth()));
        }
        DriftService.DriftReport report = drift.report(subprime, List.of());
        assertEquals("SIGNIFICANT", feature(report, "credit_score").status());
        assertEquals("SIGNIFICANT", feature(report, "property_state").status());
        assertEquals("credit_score", report.features().get(0).feature().equals("credit_score") ? "credit_score"
                : report.features().get(1).feature(), "the largest shifts are listed first");
        assertEquals(feature(drift.report(catalog, List.of()), "original_dti").psi(), feature(report, "original_dti").psi(), 1e-12,
                "an untouched feature keeps its PSI");
    }

    /** PSI by hand for a two-category feature: (0.9 - e1) ln(0.9 / e1) + (0.1 - e2) ln(0.1 / e2). */
    @Test
    void psiMatchesTheFormula() {
        List<LoanFeatures> loans = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            LoanFeatures l = catalog.get(i);
            loans.add(new LoanFeatures(l.loanId(), l.creditScore(), l.originalDti(), l.originalUpb(), l.originalCltv(), l.originalLtv(),
                    l.originalInterestRate(), l.originalLoanTerm(), l.numberOfBorrowers(), l.numberOfUnits(), l.miPercent(),
                    l.occupancyStatus(), l.propertyType(), l.loanPurpose(), l.channel(), i < 90 ? "Y" : "N", l.propertyState(),
                    l.originationMonth()));
        }
        DriftService.FeatureDrift f = feature(drift.report(loans, List.of()), "first_time_homebuyer_flag");
        double expectedYes = f.bins().stream().filter(b -> b.label().equals("Y")).findFirst().orElseThrow().expected();
        double expectedNo = f.bins().stream().filter(b -> b.label().equals("N")).findFirst().orElseThrow().expected();
        assertEquals(1.0, expectedYes + expectedNo, 1e-9);
        assertEquals((0.9 - expectedYes) * Math.log(0.9 / expectedYes) + (0.1 - expectedNo) * Math.log(0.1 / expectedNo), f.psi(), 1e-12);
        assertEquals(1 / 100.0, f.noiseFloor(), 1e-12, "(bins - 1) / n");
    }

    @Test
    void scoresAndUnknownCategoriesAreHandled() {
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            scores.add(0.0005 + 0.0004 * i);
        }
        DriftService.DriftReport report = drift.report(catalog, scores);
        assertNotNull(report.score());
        assertEquals(200, report.score().observations());
        assertEquals("calibrated_pd", report.score().feature());

        List<LoanFeatures> odd = new ArrayList<>(catalog.subList(0, 50));
        LoanFeatures l = odd.get(0);
        odd.set(0, new LoanFeatures(l.loanId(), l.creditScore(), l.originalDti(), l.originalUpb(), l.originalCltv(), l.originalLtv(),
                l.originalInterestRate(), l.originalLoanTerm(), l.numberOfBorrowers(), l.numberOfUnits(), l.miPercent(),
                l.occupancyStatus(), l.propertyType(), l.loanPurpose(), "X", l.firstTimeHomebuyerFlag(), l.propertyState(),
                l.originationMonth()));
        DriftService.FeatureDrift channel = feature(drift.report(odd, List.of()), "channel");
        assertTrue(channel.bins().stream().anyMatch(b -> b.label().equals("(not in training)") && b.actual() == 1 / 50.0));

        assertEquals("NO_DATA", feature(drift.report(List.of(), List.of()), "credit_score").status());
    }
}
