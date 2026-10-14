package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.perturbation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GranuleBuilderTest {

    private static void assertValid(Scenario s) {
        assertTrue(s.stressedMonths() >= 0 && s.stressedMonths() <= 120, s.toString());
        assertTrue(s.unemploymentShockPp() >= -5 && s.unemploymentShockPp() <= 15, s.toString());
        assertTrue(s.unemploymentShockMonths() >= 1 && s.unemploymentShockMonths() <= 60, s.toString());
        assertTrue(s.hpiShockPct() >= -30 && s.hpiShockPct() <= 60, s.toString());
        assertTrue(s.hpiShockMonths() >= 1 && s.hpiShockMonths() <= 60, s.toString());
        assertTrue(s.rateShockBp() >= -400 && s.rateShockBp() <= 600, s.toString());
        assertTrue(s.name().length() <= 30);
    }

    @Test
    void memberZeroIsTheTargetExactly() {
        for (Scenario target : Scenario.BUILT_IN) {
            List<Scenario> members = GranuleBuilder.build(target, perturbation(RoughSetMetric.PD_LIFETIME, 0.1, 0.0, 16, 7L), 7L);
            assertEquals(16, members.size());
            assertEquals(target, members.get(0));
        }
    }

    @Test
    void membersStayValidEvenAtTheEdgesOfTheirRanges() {
        Scenario edge = new Scenario("EDGE", "", 120, 15.0, 1, 0, 60.0, 60, 2.0, 600.0);
        Scenario low = new Scenario("LOW", "", 1, -5.0, 60, 120, -30.0, 1, 2.0, -400.0);
        DecisionOptions.Resolved wide = new DecisionOptions("PD_12M", 0.1, 0.0,
                new DecisionOptions.Granule("PERTURBATION", 128, 0.5, 12, null, null), 1L).resolve(32, 128);
        for (Scenario target : List.of(edge, low)) {
            for (Scenario member : GranuleBuilder.build(target, wide, 99L)) {
                assertValid(member);
            }
        }
    }

    @Test
    void zeroParametersOfTheTargetStayZero() {
        List<Scenario> members = GranuleBuilder.build(Scenario.BASELINE, perturbation(RoughSetMetric.PD_12M, 0.1, 0.0, 64, 3L), 3L);
        for (Scenario member : members) {
            assertEquals(0, member.stressedMonths());
            assertEquals(0.0, member.unemploymentShockPp());
            assertEquals(0.0, member.hpiShockPct());
            assertEquals(0.0, member.rateShockBp());
            assertEquals(Scenario.BASELINE.unemploymentShockMonths(), member.unemploymentShockMonths());
            assertEquals(Scenario.BASELINE.hpiShockMonths(), member.hpiShockMonths());
        }
    }

    @Test
    void shocksVaryWithinTheBandAndTimingWithinItsBand() {
        Scenario target = Scenario.SEVERELY_ADVERSE;
        List<Scenario> members = GranuleBuilder.build(target, perturbation(RoughSetMetric.PD_12M, 0.1, 0.0, 128, 5L), 5L);
        boolean magnitudeMoved = false;
        boolean timingMoved = false;
        for (Scenario member : members) {
            assertTrue(member.unemploymentShockPp() >= target.unemploymentShockPp() * 0.8 - 1e-12
                    && member.unemploymentShockPp() <= target.unemploymentShockPp() * 1.2 + 1e-12);
            assertTrue(Math.abs(member.hpiShockMonths() - target.hpiShockMonths()) <= 3);
            assertTrue(Math.abs(member.stressedMonths() - target.stressedMonths()) <= 3);
            magnitudeMoved |= member.unemploymentShockPp() != target.unemploymentShockPp();
            timingMoved |= member.hpiShockMonths() != target.hpiShockMonths();
        }
        assertTrue(magnitudeMoved && timingMoved, "a 128-member granule must actually vary");
    }

    @Test
    void sameSeedSameMembersDifferentSeedDifferentMembers() {
        DecisionOptions.Resolved options = perturbation(RoughSetMetric.PD_12M, 0.1, 0.0, 16, null);
        assertEquals(GranuleBuilder.build(Scenario.ADVERSE, options, 11L), GranuleBuilder.build(Scenario.ADVERSE, options, 11L));
        assertNotEquals(GranuleBuilder.build(Scenario.ADVERSE, options, 11L), GranuleBuilder.build(Scenario.ADVERSE, options, 12L));
    }

    /** Member i is a function of (seed, i) alone: building them in any order or on any threads gives the same members. */
    @Test
    void membersAreIndependentOfOrderAndThreads() throws Exception {
        long seed = 424242L;
        int n = 64;
        List<Scenario> sequential = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            sequential.add(GranuleBuilder.variant(Scenario.SEVERELY_ADVERSE, i, 0.2, 3, seed));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new java.util.Random(1));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Scenario>> futures = new ArrayList<>();
            for (int i : order) {
                futures.add(pool.submit(() -> GranuleBuilder.variant(Scenario.SEVERELY_ADVERSE, i, 0.2, 3, seed)));
            }
            Scenario[] byIndex = new Scenario[n];
            for (int k = 0; k < order.size(); k++) {
                byIndex[order.get(k)] = futures.get(k).get();
            }
            for (int i = 1; i < n; i++) {
                assertEquals(sequential.get(i - 1), byIndex[i], "member " + i);
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void scenarioSetUsesTheListedBuiltIns() {
        DecisionOptions.Resolved options = new DecisionOptions("PD_LIFETIME", 0.1, 0.0,
                new DecisionOptions.Granule("SCENARIO_SET", null, null, null, List.of("adverse", "SEVERELY_ADVERSE"), null), null).resolve(32, 128);
        assertEquals(List.of(Scenario.ADVERSE, Scenario.SEVERELY_ADVERSE), GranuleBuilder.build(Scenario.BASELINE, options, 0L));
    }

    @Test
    void unknownScenarioNamesAreRejectedWithTheValidOnes() {
        DecisionOptions.Resolved options = new DecisionOptions("PD_LIFETIME", 0.1, 0.0,
                new DecisionOptions.Granule("SCENARIO_SET", null, null, null, List.of("NOPE"), null), null).resolve(32, 128);
        TermStructureEngine.InvalidProjectionException e = assertThrows(TermStructureEngine.InvalidProjectionException.class,
                () -> GranuleBuilder.build(Scenario.BASELINE, options, 0L));
        assertTrue(e.getMessage().contains("SEVERELY_ADVERSE"), e.getMessage());
    }

    @Test
    void optionValidationNamesTheField() {
        assertValidationFails("tolerance", new DecisionOptions("PD_12M", 0.1, 0.5, null, null));
        assertValidationFails("tolerance", new DecisionOptions("PD_12M", 0.1, -0.1, null, null));
        assertValidationFails("threshold", new DecisionOptions("PD_12M", 1.5, null, null, null));
        assertValidationFails("metric", new DecisionOptions("NOPE", 0.1, null, null, null));
        assertValidationFails("members", new DecisionOptions("PD_12M", 0.1, null,
                new DecisionOptions.Granule(null, 129, null, null, null, null), null));
        assertValidationFails("shockBand", new DecisionOptions("PD_12M", 0.1, null,
                new DecisionOptions.Granule(null, null, 0.9, null, null, null), null));
        assertValidationFails("kind", new DecisionOptions("PD_12M", 0.1, null,
                new DecisionOptions.Granule("NOPE", null, null, null, null, null), null));
        assertValidationFails("regimeDraws", new DecisionOptions("PD_12M", 0.1, null,
                new DecisionOptions.Granule(null, null, null, null, null, true), null));
        assertValidationFails("seed", new DecisionOptions("PD_12M", 0.1, null, null, 1L << 53));
        assertValidationFails("seed", new DecisionOptions("PD_12M", 0.1, null, null, Long.MIN_VALUE));
    }

    private static void assertValidationFails(String field, DecisionOptions options) {
        TermStructureEngine.InvalidProjectionException e = assertThrows(TermStructureEngine.InvalidProjectionException.class,
                () -> options.resolve(DecisionOptions.DEFAULT_MEMBERS, DecisionOptions.MAX_MEMBERS));
        assertTrue(e.getMessage().contains(field), e.getMessage());
    }

    @Test
    void defaultsAreAppliedAndReported() {
        DecisionOptions.Resolved r = new DecisionOptions("pd_lifetime", 0.1, null, null, null).resolve(32, 128);
        assertEquals(RoughSetMetric.PD_LIFETIME, r.metric());
        assertEquals(0.0, r.tolerance());
        assertEquals(GranuleKind.PERTURBATION, r.kind());
        assertEquals(32, r.members());
        assertEquals(DecisionOptions.DEFAULT_SHOCK_BAND, r.shockBand());
        assertEquals(DecisionOptions.DEFAULT_TIMING_BAND_MONTHS, r.timingBandMonths());
    }
}
