package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.arthadhruva.riskengine.survival.SurvivalFixtures.ENGINE;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MODEL;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.prime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 4.4 and 5.7: a loan with no delinquency data is projected by the existing engine and the answer is the same bit for
 * bit, and the existing survival model is the one the change's baseline note froze, so nothing here moved it.
 */
class FallbackAndBaselineTest {

    /** The values frozen in openspec/changes/delinquency-aware-risk-and-uncertainty/baseline.md. */
    private static final String BASELINE_SHA256 = "d9237e4e870c64acbb6e9e0cfb749a97463c4f9b202bc2675479684b5546e620";
    private static final String BASELINE_VERSION = "survival@2026.10.13-0616";

    @Test
    void theSurvivalModelIsTheOneTheBaselineFroze() {
        assertEquals(BASELINE_SHA256, MODEL.artifactSha256());
        assertEquals(BASELINE_VERSION, MODEL.version());
        assertEquals(1.0605, MODEL.defaultScalar(), 0);
        assertEquals(0.981, MODEL.prepayScalar(), 0);
    }

    @Test
    void aRequestWithNoDelinquencyDataIsTheExistingEnginesAnswerExactly() {
        RiskProjectionService service = new RiskProjectionService(ENGINE, DelinquencyFixtures.ENGINE, DelinquencyFixtures.ROUTER,
                new IntervalService(MODEL, DelinquencyFixtures.MODEL, 20, 40, 60_000), MODEL);
        List<LoanFeatures> loans = List.of(prime(), loan("seasoned", 720, 80.0, 3.0, 360, 0.0, "TX", "2021-06"),
                loan("weak", 640, 95.0, 7.25, 360, 30.0, "FL", null));
        for (LoanFeatures l : loans) {
            for (Scenario scenario : Scenario.BUILT_IN) {
                TermStructureEngine.Request request = new TermStructureEngine.Request(l, scenario, null, null, null, null);
                TermStructureEngine.TermStructure expected = ENGINE.project(request, TermStructureEngine.MAX_MONTHS);
                RiskProjectionService.Result got = service.project(request, TermStructureEngine.MAX_MONTHS, null,
                        RiskProjectionService.IntervalOptions.NONE);
                // records compare field by field, doubles bit for bit
                assertEquals(expected.summary(), got.termStructure().summary(), l.loanId() + " / " + scenario.name());
                assertEquals(expected.ecl(), got.termStructure().ecl());
                assertEquals(expected.months(), got.termStructure().months());
                assertEquals(expected.modelVersion(), got.termStructure().modelVersion());
                assertEquals(DelinquencyRouter.NO_DATA, got.fallbackReason());
                assertTrue(got.modelUsed().startsWith("survival@"));
                assertNull(got.intervals());
            }
        }
    }

    @Test
    void aStatusAloneStillRunsTheExistingEngineAndStagesItAsBefore() {
        RiskProjectionService service = new RiskProjectionService(ENGINE, DelinquencyFixtures.ENGINE, DelinquencyFixtures.ROUTER,
                new IntervalService(MODEL, DelinquencyFixtures.MODEL, 20, 40, 60_000), MODEL);
        TermStructureEngine.Request request = new TermStructureEngine.Request(prime(), Scenario.BASELINE, null, null, 120, null);
        TermStructureEngine.TermStructure expected = ENGINE.project(request, 12);
        RiskProjectionService.Result got = service.project(request, 12, null, RiskProjectionService.IntervalOptions.NONE);
        assertEquals(expected.summary(), got.termStructure().summary());
        assertEquals(3, got.termStructure().ecl().stage(), "90+ days past due is stage 3, as before");
        assertEquals(DelinquencyRouter.INCOMPLETE, got.fallbackReason());
    }
}
