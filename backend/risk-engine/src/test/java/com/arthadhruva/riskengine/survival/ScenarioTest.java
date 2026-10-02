package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioTest {

    @Test
    void unemploymentRampsUpThenFades() {
        Scenario s = Scenario.ADVERSE;  // +3 pp over 12 months, fading over 36
        assertEquals(0.0, s.unemploymentDelta(0), 0.0);
        assertEquals(1.5, s.unemploymentDelta(6), 1e-12);
        assertEquals(3.0, s.unemploymentDelta(12), 1e-12);
        assertEquals(1.5, s.unemploymentDelta(30), 1e-12);
        assertEquals(0.0, s.unemploymentDelta(48), 1e-12);
        assertEquals(0.0, s.unemploymentDelta(400), 0.0);

        Scenario permanent = new Scenario("P", "", 0, 2.0, 6, 0, 0.0, 12, 0.0, 0.0);
        assertEquals(2.0, permanent.unemploymentDelta(240), 0.0);
    }

    @Test
    void housePricesFallToTheTroughThenGrow() {
        Scenario s = Scenario.SEVERELY_ADVERSE;  // -25% over 24 months, then +2% a year
        assertEquals(1.0, s.hpiFactor(0), 0.0);
        assertEquals(0.75, s.hpiFactor(24), 1e-12);
        assertEquals(0.75 * 1.02, s.hpiFactor(36), 1e-12);
        for (int k = 1; k <= 24; k++) {
            assertTrue(s.hpiFactor(k) < s.hpiFactor(k - 1));
        }
        // the path is continuous where the shock ends
        assertEquals(s.hpiFactor(24) * Math.pow(1.02, 1 / 12.0), s.hpiFactor(25), 1e-12);
        assertEquals(1.03, Scenario.BASELINE.hpiFactor(12), 1e-12);
    }

    @Test
    void builtInScenariosAreFoundByNameWhateverTheCase() {
        assertSame(Scenario.BASELINE, Scenario.named(null));
        assertSame(Scenario.BASELINE, Scenario.named(" "));
        assertSame(Scenario.SEVERELY_ADVERSE, Scenario.named("severely_adverse"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.named("DOOM"));
        assertEquals(5, Scenario.BUILT_IN.size());
    }

    @Test
    void regimeOutlookStartsFromTheDecodedRegimeAndSettlesAtTheStationaryMix() {
        SurvivalModel model = SurvivalFixtures.MODEL;
        YearMonth asOf = model.regimeAsOf();
        double[] today = model.regimeDistribution(asOf, asOf);
        assertEquals(1.0, today[model.currentRegime()], 0.0);

        double[][] p = model.transitionMatrix();
        double stationaryStressed = p[0][1] / (p[0][1] + p[1][0]);
        double[] far = model.regimeDistribution(asOf.plusMonths(2000), asOf);
        assertEquals(stationaryStressed, far[SurvivalModel.STRESSED], 1e-9);
        assertEquals(1.0, far[0] + far[1], 1e-12);

        // A month already decoded is known, not forecast: mid-2021 was stressed, 2018 calm.
        assertEquals(1.0, model.regimeDistribution(YearMonth.of(2021, 6), asOf)[SurvivalModel.STRESSED], 0.0);
        assertEquals(1.0, model.regimeDistribution(YearMonth.of(2018, 6), asOf)[SurvivalModel.CALM], 0.0);
        // Standing in 2018, the outlook for 2021 is a forecast from a calm start.
        double[] from2018 = model.regimeDistribution(YearMonth.of(2021, 6), YearMonth.of(2018, 6));
        assertTrue(from2018[SurvivalModel.STRESSED] > 0 && from2018[SurvivalModel.STRESSED] < stationaryStressed);
    }
}
