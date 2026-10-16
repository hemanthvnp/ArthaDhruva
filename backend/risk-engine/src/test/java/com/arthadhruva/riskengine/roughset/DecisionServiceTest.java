package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.ENGINE;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.SERVICE;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.perturbation;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.prime;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.request;
import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.weak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionServiceTest {

    private static double direct(RoughSetMetric metric, Scenario scenario) {
        return metric.of(ENGINE.projectAll(List.of(request(prime(), scenario))).get(0));
    }

    /** One member is the unperturbed target, so the decision collapses to comparing the engine's own figure. */
    @Test
    void aSingleMemberEqualsTheUnperturbedEngineResult() {
        double pd = direct(RoughSetMetric.PD_LIFETIME, Scenario.ADVERSE);
        TermStructureEngine.Request base = request(prime(), Scenario.ADVERSE);

        DecisionService.Decision below = SERVICE.decide(base, perturbation(RoughSetMetric.PD_LIFETIME, pd - 1e-6, 0.0, 1, 1L));
        assertEquals(Region.CERTAIN_HIGH, below.region());
        assertEquals(pd, below.baseMetric(), 0.0);
        assertEquals(pd, below.min(), 0.0);
        assertEquals(pd, below.max(), 0.0);
        assertEquals(0.0, below.width(), 0.0);
        assertEquals(1, below.members());

        DecisionService.Decision above = SERVICE.decide(base, perturbation(RoughSetMetric.PD_LIFETIME, Math.min(1.0, pd + 1e-6), 0.0, 1, 1L));
        assertEquals(Region.CERTAIN_LOW, above.region());
    }

    @Test
    void theSameRequestGivesTheSameDecision() {
        TermStructureEngine.Request base = request(prime(), Scenario.SEVERELY_ADVERSE);
        double pd = direct(RoughSetMetric.PD_LIFETIME, Scenario.SEVERELY_ADVERSE);
        DecisionOptions.Resolved options = perturbation(RoughSetMetric.PD_LIFETIME, pd, 0.0, 16, null);
        DecisionService.Decision a = SERVICE.decide(base, options);
        DecisionService.Decision b = SERVICE.decide(base, options);
        assertEquals(a, b);
        assertEquals(a.seed(), b.seed());
        assertEquals(DecisionService.METHOD_PERTURBATION, a.method());
    }

    /** The seed is shown in the console and may be sent back: it has to survive a JSON number in JavaScript. */
    @Test
    void aDerivedSeedIsExactlyRepresentableAsAJavaScriptNumber() {
        for (Scenario scenario : Scenario.BUILT_IN) {
            long seed = SERVICE.decide(request(prime(), scenario), perturbation(RoughSetMetric.PD_12M, 0.05, 0.0, 2, null)).seed();
            assertTrue(seed >= 0 && seed <= DecisionOptions.MAX_SEED, scenario.name() + " " + seed);
            assertEquals(seed, (long) (double) seed, "no precision lost in a double");
        }
    }

    @Test
    void anExplicitSeedIsReturnedAndOverridesTheDerivedOne() {
        TermStructureEngine.Request base = request(prime(), Scenario.ADVERSE);
        assertEquals(1234L, SERVICE.decide(base, perturbation(RoughSetMetric.PD_12M, 0.05, 0.0, 4, 1234L)).seed());
    }

    /** A threshold set at the unperturbed figure puts a stressed loan on the boundary: perturbing the stress moves it across. */
    @Test
    void aThresholdAtTheUnperturbedFigureIsOnTheBoundary() {
        double pd = direct(RoughSetMetric.PD_LIFETIME, Scenario.SEVERELY_ADVERSE);
        DecisionService.Decision d = SERVICE.decide(request(prime(), Scenario.SEVERELY_ADVERSE),
                perturbation(RoughSetMetric.PD_LIFETIME, pd, 0.0, 32, 5L));
        assertEquals(Region.BOUNDARY, d.region(), "member 0 is at the threshold and the others fall on both sides of it");
        assertTrue(d.high() >= 1 && d.high() < d.members());
        assertTrue(d.min() < pd && d.max() > pd, "stronger and weaker stress variants must straddle the target");
        assertTrue(d.width() > 0);
    }

    @Test
    void farFromTheThresholdTheLoanIsDecided() {
        DecisionService.Decision low = SERVICE.decide(request(prime(), Scenario.BASELINE),
                perturbation(RoughSetMetric.PD_12M, 0.9, 0.0, 16, 5L));
        assertEquals(Region.CERTAIN_LOW, low.region());
        DecisionService.Decision high = SERVICE.decide(request(weak(), Scenario.SEVERELY_ADVERSE),
                perturbation(RoughSetMetric.PD_LIFETIME, 0.0, 0.0, 16, 5L));
        assertEquals(Region.CERTAIN_HIGH, high.region());
    }

    @Test
    void theEclRateMetricIsLossOverExposure() {
        TermStructureEngine.Outcome o = ENGINE.projectAll(List.of(request(prime(), Scenario.ADVERSE))).get(0);
        assertEquals(o.ecl().eclLifetime() / o.ecl().exposure(), RoughSetMetric.ECL_LIFETIME_RATE.of(o), 1e-15);
    }

    @Test
    void thresholdMonotonicityHoldsOnRealEngineOutput() {
        TermStructureEngine.Request base = request(prime(), Scenario.ADVERSE);
        double pd = direct(RoughSetMetric.PD_LIFETIME, Scenario.ADVERSE);
        Region previous = Region.CERTAIN_HIGH;
        for (double factor : new double[]{0.5, 0.9, 1.0, 1.1, 1.5, 3.0}) {
            Region now = SERVICE.decide(base, perturbation(RoughSetMetric.PD_LIFETIME, Math.min(1.0, pd * factor), 0.0, 16, 9L)).region();
            // CERTAIN_HIGH -> BOUNDARY -> CERTAIN_LOW as the threshold rises, never back
            assertTrue(now.ordinal() == previous.ordinal() || rank(now) >= rank(previous), factor + ": " + previous + " -> " + now);
            previous = now;
        }
    }

    private static int rank(Region r) {
        return switch (r) {
            case CERTAIN_HIGH -> 0;
            case BOUNDARY -> 1;
            case CERTAIN_LOW -> 2;
        };
    }

    @Test
    void scenarioSetIsHighOnlyIfHighUnderEveryListedScenario() {
        double adverse = direct(RoughSetMetric.PD_LIFETIME, Scenario.ADVERSE);
        double severe = direct(RoughSetMetric.PD_LIFETIME, Scenario.SEVERELY_ADVERSE);
        assertTrue(severe > adverse, "the severe scenario must be worse for this loan");
        TermStructureEngine.Request base = request(prime(), Scenario.BASELINE);

        DecisionService.Decision all = SERVICE.decide(base, setOptions((adverse + severe) / 2 > adverse ? adverse - 1e-9 : adverse));
        assertEquals(Region.CERTAIN_HIGH, all.region());
        assertEquals(List.of("ADVERSE", "SEVERELY_ADVERSE"), all.scenarios());
        assertEquals(DecisionService.METHOD_SCENARIO_SET, all.method());

        DecisionService.Decision between = SERVICE.decide(base, setOptions((adverse + severe) / 2));
        assertEquals(Region.BOUNDARY, between.region());
        assertEquals(1, between.high());

        assertEquals(Region.CERTAIN_LOW, SERVICE.decide(base, setOptions(Math.min(1.0, severe + 1e-6))).region());
    }

    private static DecisionOptions.Resolved setOptions(double threshold) {
        return new DecisionOptions("PD_LIFETIME", threshold, 0.0,
                new DecisionOptions.Granule("SCENARIO_SET", null, null, null, List.of("ADVERSE", "SEVERELY_ADVERSE"), null), null)
                .resolve(DecisionOptions.DEFAULT_MEMBERS, DecisionOptions.MAX_MEMBERS);
    }

    /** The flag qualifies the region; it never changes it. */
    @Test
    void extrapolationIsFlaggedAndDoesNotChangeTheRegion() {
        Scenario extreme = new Scenario("EXTREME", "", 36, 15.0, 6, 60, 30.0, 12, 2.0, 0.0);
        TermStructureEngine.Request base = request(prime(), extreme);
        TermStructureEngine.Outcome target = ENGINE.projectAll(List.of(base)).get(0);
        List<TermStructureEngine.Extrapolation> targetExtrapolations = DecisionService.stressDriven(ENGINE.extrapolations(target));
        assertFalse(targetExtrapolations.isEmpty(), "the test scenario must leave the trained range for this to mean anything");

        double pd = RoughSetMetric.PD_LIFETIME.of(target);
        DecisionService.Decision d = SERVICE.decide(base, perturbation(RoughSetMetric.PD_LIFETIME, pd, 0.0, 8, 3L));
        assertTrue(d.extrapolated());
        assertFalse(d.extrapolations().isEmpty());
        assertTrue(d.extrapolations().stream().map(TermStructureEngine.Extrapolation::driver)
                .toList().containsAll(targetExtrapolations.stream().map(TermStructureEngine.Extrapolation::driver).distinct().toList()));

        // the same metrics classified directly give the same region
        double[] metrics = ENGINE.projectAll(GranuleBuilder.build(extreme, perturbation(RoughSetMetric.PD_LIFETIME, pd, 0.0, 8, 3L), 3L)
                .stream().map(s -> request(prime(), s)).toList()).stream().mapToDouble(RoughSetMetric.PD_LIFETIME::of).toArray();
        assertEquals(RegionClassifier.classify(metrics, pd, 0.0).region(), d.region());
    }

    /** loan_age beyond the training window (about nine years) is structural for a 30-year loan; it must not trip the flag. */
    @Test
    void structuralExtrapolationDoesNotTripTheFlag() {
        TermStructureEngine.Outcome baseline = ENGINE.projectAll(List.of(request(prime(), Scenario.BASELINE))).get(0);
        assertTrue(ENGINE.extrapolations(baseline).stream().anyMatch(e -> e.driver().equals("loan_age")),
                "premise: the engine does report loan_age beyond the trained window for a 30-year loan");
        assertTrue(DecisionService.stressDriven(ENGINE.extrapolations(baseline)).isEmpty());
    }

    @Test
    void aStressedScenarioIsFlaggedForTheDriversItMoves() {
        // prices halving in a year: steeper than any 12 months in training, in every variant of the band
        Scenario collapse = new Scenario("COLLAPSE", "", 0, 0.0, 12, 0, 50.0, 12, 2.0, 0.0);
        DecisionService.Decision d = SERVICE.decide(request(prime(), collapse),
                perturbation(RoughSetMetric.PD_LIFETIME, 0.05, 0.0, 8, 1L));
        assertTrue(d.extrapolated());
        assertTrue(d.extrapolations().stream().anyMatch(e -> e.driver().equals("hpi_change_12m")), d.extrapolations().toString());
        assertTrue(d.extrapolations().stream().noneMatch(e -> e.driver().equals("loan_age")));
    }

    @Test
    void anUnstressedBaselineDoesNotExtrapolate() {
        assertFalse(SERVICE.decide(request(prime(), Scenario.BASELINE), perturbation(RoughSetMetric.PD_12M, 0.5, 0.0, 8, 1L)).extrapolated());
    }
}
