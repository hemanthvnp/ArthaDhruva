package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine against an independent implementation: backend/golden_term_structure.py rebuilds every
 * model input, the regime recursion and the ECL arithmetic in plain Python from the same exported
 * artifacts and writes what it gets to term_structure_golden.json. Any change that alters one feature,
 * one rounding or one formula on either side fails here.
 */
class TermStructureGoldenTest {

    private static final double RELATIVE = 1e-6;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode golden;
    private static SurvivalModel model;
    private static TermStructureEngine engine;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = TermStructureGoldenTest.class.getClassLoader().getResourceAsStream("term_structure_golden.json")) {
            golden = MAPPER.readTree(is.readAllBytes());
        }
        model = new SurvivalModel(true);
        engine = new TermStructureEngine(model, new MarketData(), new LgdPredictor(),
                golden.get("liquidation_haircut").asDouble(), golden.get("sicr_relative").asDouble(),
                golden.get("sicr_absolute").asDouble());
    }

    @Test
    void theGoldenFileBelongsToTheShippedModel() {
        assertEquals("survival@" + golden.get("model_version").asString(), model.version(),
                "regenerate with: python golden_term_structure.py");
    }

    @Test
    void javaEngineReproducesThePythonReference() {
        assertTrue(golden.get("cases").size() >= 10);
        for (JsonNode c : golden.get("cases")) {
            JsonNode request = c.get("request");
            JsonNode expected = c.get("expected");
            LoanFeatures loan = MAPPER.treeToValue(request.get("loan"), LoanFeatures.class);
            Scenario scenario = MAPPER.treeToValue(request.get("scenario"), Scenario.class);
            String name = loan.loanId() + " / " + scenario.name();
            TermStructureEngine.TermStructure t = engine.project(new TermStructureEngine.Request(loan, scenario,
                    integer(request, "monthsOnBook"), number(request, "currentBalance"), integer(request, "daysPastDue"),
                    number(request, "originationPd12m")), TermStructureEngine.MAX_MONTHS);

            assertEquals(expected.get("monthsOnBook").asInt(), t.monthsOnBook(), name);
            assertEquals(expected.get("remainingMonths").asInt(), t.remainingMonths(), name);
            assertEquals(expected.get("remainingMonths").asInt(), t.months().size(), name);
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
            close(expected, "rateSpread", t.assumptions().rateSpread(), name);

            for (JsonNode point : expected.get("points")) {
                TermStructureEngine.MonthPoint m = t.months().get(point.get("k").asInt() - 1);
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
                close(point, "unemployment", m.unemployment(), at);
                close(point, "housePriceIndex", m.housePriceIndex(), at);
                close(point, "mtmLtv", m.mtmLtv(), at);
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
