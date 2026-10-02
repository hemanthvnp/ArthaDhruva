package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.ml.MarketData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplanationServiceTest {

    private static ModelService model;
    private static ExplanationService explainer;

    @BeforeAll
    static void load() throws Exception {
        model = new ModelService(new MarketData());
        explainer = new ExplanationService(model);
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    private static LoanFeatures loan(int credit, double ltv, double dti, String state) {
        return new LoanFeatures("T", credit, dti, 250000.0, ltv, ltv, 6.5, 360, 2, 1, 0.0, "P", "SF", "P", "R", "N", state, null);
    }

    private static double sum(ExplanationService.Explanation e) {
        return e.contributions().stream().mapToDouble(ScoreResponse.Attribution::contribution).sum();
    }

    /** Shapley efficiency: the attribution accounts for the whole gap between this loan and the reference. */
    @Test
    void contributionsSumToTheLoansPdMinusTheBaseline() {
        // differs from the reference loan in many features -> sampled orderings
        LoanFeatures many = new LoanFeatures("T", 610, 47.0, 410000.0, 97.0, 95.0, 7.4, 240, 1, 2, 30.0,
                "I", "CO", "C", "B", "Y", "FL", "2022-03");
        // differs in a handful -> exact enumeration
        LoanFeatures few = loan(700, 80, 35, "CA");
        for (LoanFeatures l : List.of(many, few)) {
            ExplanationService.Explanation e = explainer.explain(l);
            double pd = model.score(l).calibratedProbability();
            assertEquals(pd - e.baselineProbability(), sum(e), 1e-7);
        }
    }

    @Test
    void theSameLoanAlwaysGetsTheSameExplanation() {
        LoanFeatures l = new LoanFeatures("T", 610, 47.0, 410000.0, 97.0, 95.0, 7.4, 240, 1, 2, 30.0,
                "I", "CO", "C", "B", "Y", "FL", "2022-03");
        assertEquals(explainer.explain(l), explainer.explain(l));
    }

    @Test
    void explanationsAreLoanSpecificAndPointTheRightWay() {
        ExplanationService.Explanation risky = explainer.explain(loan(560, 97, 48, "FL"));
        ExplanationService.Explanation safe = explainer.explain(loan(800, 55, 18, "FL"));

        assertFalse(risky.contributions().isEmpty());
        assertTrue(sum(risky) > sum(safe), "a riskier profile's attributions push risk up relative to a safe one");
        assertEquals("credit_score", risky.contributions().get(0).feature(), "contributions are ordered by magnitude");
    }

    @Test
    void reasonCodesNameOnlyWhatRaisesRisk() {
        ExplanationService.Explanation risky = explainer.explain(loan(560, 97, 48, "FL"));
        assertFalse(risky.reasonCodes().isEmpty());
        assertTrue(risky.reasonCodes().size() <= 4);
        assertEquals("R01", risky.reasonCodes().get(0).code(), "a 560 credit score is the principal reason");
        assertTrue(risky.reasonCodes().stream().allMatch(r -> r.contribution() > 0));
        // LTV and CLTV are reported to a borrower as one reason
        assertTrue(risky.reasonCodes().stream().filter(r -> r.code().equals("R03")).count() <= 1);

        ExplanationService.Explanation safe = explainer.explain(loan(800, 55, 18, "FL"));
        assertTrue(safe.reasonCodes().stream().noneMatch(r -> r.code().equals("R01")),
                "an 800 credit score lowers risk and is never a reason");
    }

    /** A reason is something a borrower could be told: it says what the loan is, and it is large enough to matter. */
    @Test
    void reasonsDescribeTheLoanAsItIsAndLeaveOutWhatBarelyMatters() {
        // identical to the reference loan except for a refinance without cash out
        LoanFeatures refinance = new LoanFeatures("T", 757, 37.0, 243000.0, 79.0, 79.0, 6.5, 360, 1, 1, 0.0,
                "P", "SF", "N", "R", "N", "CA", null);
        for (ScoreResponse.ReasonCode reason : explainer.explain(refinance).reasonCodes()) {
            if (reason.feature().equals("loan_purpose")) {
                assertEquals("Loan purpose: refinance without cash out", reason.description());
            }
            assertFalse(reason.description().contains("cash-out"), reason.description());
        }
        LoanFeatures investor = new LoanFeatures("T", 640, 45.0, 243000.0, 90.0, 90.0, 7.5, 360, 1, 1, 0.0,
                "I", "CO", "C", "B", "N", "FL", null);
        ExplanationService.Explanation explained = explainer.explain(investor);
        double principal = explained.reasonCodes().get(0).contribution();
        for (ScoreResponse.ReasonCode reason : explained.reasonCodes()) {
            assertTrue(reason.contribution() >= 1e-4 && reason.contribution() >= 0.05 * principal,
                    reason.description() + " adds " + reason.contribution());
            switch (reason.feature()) {
                case "occupancy_status" -> assertEquals("Occupancy: investment property", reason.description());
                case "loan_purpose" -> assertEquals("Loan purpose: cash-out refinance", reason.description());
                case "property_type" -> assertEquals("Property type: condominium", reason.description());
                case "channel" -> assertEquals("Origination channel: broker", reason.description());
                default -> assertFalse(reason.description().isBlank());
            }
        }
        // The reference loan itself differs from nothing, so nothing counts against it.
        LoanFeatures reference = new LoanFeatures("T", 757, 37.0, 243000.0, 79.0, 79.0, 6.5, 360, 1, 1, 0.0,
                "P", "SF", "P", "R", "N", "CA", null);
        assertTrue(explainer.explain(reference).reasonCodes().stream().allMatch(r -> r.contribution() >= 1e-4));
    }
}
