package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.Scenario;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision layer against an independent implementation: backend/golden_rough_set.py redoes splitmix64, the
 * clamped perturbation of a scenario, the FNV-1a seed hash and the variable-precision rough-set rule in plain
 * Python and writes what it gets to rough_set_golden.json. Members, regions and counts must match exactly;
 * derived widths and medians to 1e-9. The engine's own figures are not tested here (TermStructureGoldenTest
 * holds them to their own Python reference).
 */
class RoughSetGoldenTest {

    private static final double TOLERANCE = 1e-9;
    private static JsonNode golden;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = RoughSetGoldenTest.class.getClassLoader().getResourceAsStream("rough_set_golden.json")) {
            golden = new ObjectMapper().readTree(is.readAllBytes());
        }
    }

    private static Scenario scenario(JsonNode n) {
        return new Scenario(n.get("name").asString(), "", n.get("stressedMonths").asInt(), n.get("unemploymentShockPp").asDouble(),
                n.get("unemploymentShockMonths").asInt(), n.get("unemploymentRecoveryMonths").asInt(), n.get("hpiShockPct").asDouble(),
                n.get("hpiShockMonths").asInt(), n.get("hpiAnnualGrowthPct").asDouble(), n.get("rateShockBp").asDouble());
    }

    @Test
    void splitMixStreamsMatchThePythonReference() {
        assertTrue(golden.get("streams").size() >= 16);
        for (JsonNode c : golden.get("streams")) {
            SplitMix64 rng = SplitMix64.forMember(c.get("seed").asLong(), c.get("index").asInt());
            for (JsonNode expected : c.get("firstLongs")) {
                assertEquals(expected.asLong(), rng.nextLong(), "seed " + c.get("seed") + " index " + c.get("index"));
            }
        }
    }

    @Test
    void seedHashMatchesThePythonReference() {
        for (JsonNode c : golden.get("hashes")) {
            assertEquals(c.get("hash").asLong(), SplitMix64.fnv1a(c.get("text").asString()), c.get("text").asString());
        }
    }

    @Test
    void perturbedMembersMatchThePythonReferenceExactly() {
        assertTrue(golden.get("generator").size() >= 200);
        for (JsonNode c : golden.get("generator")) {
            Scenario target = scenario(c.get("target"));
            long seed = c.get("seed").asLong();
            double band = c.get("shockBand").asDouble();
            int timing = c.get("timingBandMonths").asInt();
            int index = 1;
            for (JsonNode expectedNode : c.get("members")) {
                Scenario expected = scenario(expectedNode);
                Scenario actual = GranuleBuilder.variant(target, index, band, timing, seed);
                String where = target.name() + " seed " + seed + " band " + band + "/" + timing + " member " + index;
                assertEquals(expected.name(), actual.name(), where);
                assertEquals(expected.stressedMonths(), actual.stressedMonths(), where);
                assertEquals(expected.unemploymentShockPp(), actual.unemploymentShockPp(), 0.0, where);
                assertEquals(expected.unemploymentShockMonths(), actual.unemploymentShockMonths(), where);
                assertEquals(expected.unemploymentRecoveryMonths(), actual.unemploymentRecoveryMonths(), where);
                assertEquals(expected.hpiShockPct(), actual.hpiShockPct(), 0.0, where);
                assertEquals(expected.hpiShockMonths(), actual.hpiShockMonths(), where);
                assertEquals(expected.hpiAnnualGrowthPct(), actual.hpiAnnualGrowthPct(), 0.0, where);
                assertEquals(expected.rateShockBp(), actual.rateShockBp(), 0.0, where);
                index++;
            }
        }
    }

    @Test
    void classifierMatchesThePythonReference() {
        assertTrue(golden.get("classifier").size() >= 400);
        for (JsonNode c : golden.get("classifier")) {
            double[] metrics = new double[c.get("metrics").size()];
            for (int i = 0; i < metrics.length; i++) {
                metrics[i] = c.get("metrics").get(i).asDouble();
            }
            RegionClassifier.Classification actual = RegionClassifier.classify(metrics, c.get("threshold").asDouble(),
                    c.get("tolerance").asDouble());
            JsonNode expected = c.get("expected");
            String where = c.toString();
            assertEquals(Region.valueOf(expected.get("region").asString()), actual.region(), where);
            assertEquals(expected.get("high").asInt(), actual.high(), where);
            assertEquals(expected.get("members").asInt(), actual.members(), where);
            assertEquals(expected.get("min").asDouble(), actual.min(), TOLERANCE, where);
            assertEquals(expected.get("median").asDouble(), actual.median(), TOLERANCE, where);
            assertEquals(expected.get("max").asDouble(), actual.max(), TOLERANCE, where);
            assertEquals(expected.get("width").asDouble(), actual.width(), TOLERANCE, where);
        }
    }
}
