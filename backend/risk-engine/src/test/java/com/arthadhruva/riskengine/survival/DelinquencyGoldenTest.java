package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-state engine against an independent implementation: backend/golden_multistate.py rebuilds the scenario
 * rows, runs the same ONNX graph, applies the same floor and history rule, and does the recursion over (regime, state)
 * and the loss arithmetic in plain Python, then writes what it gets to delinquency_golden.json. Any change that alters a
 * feature, a probability or a formula on either side fails here, to 1e-6.
 */
class DelinquencyGoldenTest {

    private static final double RELATIVE = 1e-6;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode golden;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = DelinquencyGoldenTest.class.getClassLoader().getResourceAsStream("delinquency_golden.json")) {
            golden = MAPPER.readTree(is.readAllBytes());
        }
    }

    @Test
    void theGoldenFileBelongsToTheShippedModels() {
        assertEquals("delinquency@" + golden.get("model_version").asString(), DelinquencyFixtures.MODEL.version(),
                "regenerate with: python golden_multistate.py");
        assertEquals("survival@" + golden.get("survival_model_version").asString(), SurvivalFixtures.MODEL.version(),
                "regenerate with: python golden_multistate.py");
    }

    @Test
    void javaEngineReproducesThePythonReference() {
        assertTrue(golden.get("cases").size() >= 10);
        MultiStateEngine engine = new MultiStateEngine(SurvivalFixtures.ENGINE, SurvivalFixtures.MODEL, DelinquencyFixtures.MODEL,
                SurvivalFixtures.MARKET, golden.get("liquidation_haircut").asDouble(), golden.get("sicr_relative").asDouble(),
                golden.get("sicr_absolute").asDouble());
        for (JsonNode c : golden.get("cases")) {
            JsonNode request = c.get("request");
            JsonNode expected = c.get("expected");
            LoanFeatures loan = MAPPER.treeToValue(request.get("loan"), LoanFeatures.class);
            Scenario scenario = MAPPER.treeToValue(request.get("scenario"), Scenario.class);
            JsonNode h = request.get("history");
            DelinquencyModel.History history = new DelinquencyModel.History(h.get("episodes").asInt(), h.get("monthsSinceCure").asInt(),
                    h.get("maxState").asInt());
            Integer dpd = integer(request, "daysPastDue");
            String name = loan.loanId() + " / " + scenario.name() + " / " + dpd + " dpd";
            TermStructureEngine.Request base = new TermStructureEngine.Request(loan, scenario, integer(request, "monthsOnBook"),
                    number(request, "currentBalance"), dpd, number(request, "originationPd12m"));
            MultiStateEngine.Outcome outcome = engine.projectAll(List.of(
                    new MultiStateEngine.Request(base, expected.get("startState").asInt(), history))).get(0);
            TermStructureEngine.TermStructure t = engine.view(outcome, TermStructureEngine.MAX_MONTHS);

            assertEquals(expected.get("monthsOnBook").asInt(), t.monthsOnBook(), name);
            assertEquals(expected.get("remainingMonths").asInt(), t.remainingMonths(), name);
            close(expected, "pd12m", t.summary().pd12m(), name);
            close(expected, "pd24m", t.summary().pd24m(), name);
            close(expected, "pdLifetime", t.summary().pdLifetime(), name);
            close(expected, "prepayLifetime", t.summary().prepayLifetime(), name);
            close(expected, "expectedLifeMonths", t.summary().expectedLifeMonths(), name);
            close(expected, "maturityProbability", t.summary().maturityProbability(), name);
            assertEquals(expected.get("stage").asInt(), t.ecl().stage(), name);
            if (expected.get("referencePd12m").isNull()) {
                assertNull(t.ecl().referencePd12m(), name);
            } else {
                close(expected, "referencePd12m", t.ecl().referencePd12m(), name);
            }
            close(expected, "exposure", t.ecl().exposure(), name);
            close(expected, "lgd", t.ecl().lgd(), name);
            close(expected, "lgdPeak", t.ecl().lgdPeak(), name);
            close(expected, "ecl12m", t.ecl().ecl12m(), name);
            close(expected, "eclLifetime", t.ecl().eclLifetime(), name);
            close(expected, "eclIfrs9", t.ecl().eclIfrs9(), name);
            close(expected, "eclCecl", t.ecl().eclCecl(), name);

            for (JsonNode point : expected.get("points")) {
                int i = point.get("k").asInt() - 1;
                TermStructureEngine.MonthPoint m = t.months().get(i);
                String at = name + " month " + point.get("k").asInt();
                assertEquals(point.get("month").asString(), m.month(), at);
                assertEquals(point.get("loanAge").asInt(), m.loanAge(), at);
                close(point, "survival", m.survival(), at);
                close(point, "marginalDefault", m.marginalDefault(), at);
                close(point, "marginalPrepay", m.marginalPrepay(), at);
                close(point, "cumulativeDefault", m.cumulativeDefault(), at);
                close(point, "stressedProbability", m.stressedProbability(), at);
                close(point, "exposure", m.exposure(), at);
                close(point, "lgd", m.lgd(), at);
                close(point, "discountedExpectedLoss", m.discountedExpectedLoss(), at);
                for (int s = 0; s < DelinquencyModel.STATES; s++) {
                    double want = point.get("byState").get(s).asDouble();
                    assertEquals(want, outcome.curve().byState()[i][s], Math.max(1e-12, Math.abs(want) * RELATIVE),
                            at + " active in state " + s);
                }
            }
        }
    }

    private static void close(JsonNode expected, String field, double actual, String where) {
        double want = expected.get(field).asDouble();
        assertEquals(want, actual, Math.max(1e-12, Math.abs(want) * RELATIVE), where + ": " + field);
    }

    private static Integer integer(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static Double number(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asDouble() : null;
    }
}
