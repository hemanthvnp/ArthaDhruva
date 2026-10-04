package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.ml.Quantizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the origination PD service to an independent Python implementation (backend/golden_pd_model.py): the
 * feature vector it builds (rate spread, rounding grid, category codes, column order), the raw ONNX
 * probability, and the isotonic calibration. Plain instantiation, no Spring context or infrastructure.
 */
class PdModelParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode golden;
    private static ModelService model;
    private static MarketData market;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = PdModelParityTest.class.getClassLoader().getResourceAsStream("pd_model_golden.json")) {
            golden = MAPPER.readTree(is.readAllBytes());
        }
        market = new MarketData();
        model = new ModelService(market);
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    @Test
    void theGoldenFileBelongsToTheShippedModel() {
        assertEquals("pd_24m@" + golden.get("model_version").asString(), model.version(),
                "regenerate with: python golden_pd_model.py");
        assertEquals(model.declaredArtifactSha256(), golden.get("artifact_sha256").asString(),
                "regenerate with: python golden_pd_model.py");
        assertEquals(model.featureNames(), strings(golden.get("features")), "model feature order changed");
    }

    @Test
    void javaBuildsTheSameFeatureVectorAsPython() {
        for (JsonNode c : golden.get("cases")) {
            LoanFeatures loan = MAPPER.treeToValue(c.get("loan"), LoanFeatures.class);
            float[] expected = new float[c.get("featureVector").size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (float) c.get("featureVector").get(i).asDouble();
            }
            // exact: the grid exists so that no float difference can move a tree split
            assertArrayEquals(expected, model.featureVectorFor(loan), 0.0f, loan.loanId());
            assertEquals(c.get("rateSpread").asDouble(), model.featureVectorFor(loan)[model.featureNames().indexOf("rate_spread")],
                    0.0, loan.loanId() + ": rate_spread");
        }
    }

    /**
     * The spread is subtracted in float64, narrowed to float32 and only then put on the 3-decimal grid, exactly as
     * training's with_spread does. On an exact tie that differs from rounding the double: 6.5 - PMMS(2024-06)=6.9175
     * is -0.41750000000000043 in float64, which rounds to -0.418, but its float32 is on the other side of the tie and
     * rounds to -0.417 -- the value the model was trained on. Both services (this and the survival model) share it.
     */
    @Test
    void theRateSpreadIsNarrowedToFloat32BeforeItIsRounded() {
        LoanFeatures tie = new LoanFeatures("T", 700, 40.0, 300000.0, 85.0, 85.0, 6.5, 360, 1, 1, 12.0,
                "P", "SF", "P", "R", "N", "TX", "2024-08");
        double spread = tie.originalInterestRate() - market.mortgageRate(java.time.YearMonth.of(2024, 6));
        assertEquals(-0.418f, Quantizer.round(spread, 3), 0.0f, "rounding the double would give this");
        assertEquals(-0.417f, Quantizer.roundAfterFloat32(spread, 3), 0.0f);
        assertEquals(-0.417f, model.featureVectorFor(tie)[model.featureNames().indexOf("rate_spread")], 0.0f);
    }

    @Test
    void javaReproducesPythonRawAndCalibratedProbabilities() {
        for (JsonNode c : golden.get("cases")) {
            LoanFeatures loan = MAPPER.treeToValue(c.get("loan"), LoanFeatures.class);
            ScoreResponse response = model.score(loan);
            // same ONNX graph on both sides; what differs is the runtime build and float32 accumulation
            assertEquals(c.get("rawProbability").asDouble(), response.rawProbability(), 1e-6, loan.loanId() + ": raw");
            assertEquals(c.get("calibratedProbability").asDouble(), response.calibratedProbability(), 1e-6,
                    loan.loanId() + ": calibrated");
        }
    }

    @Test
    void theBatchPathAgreesWithTheSingleLoanPath() {
        List<LoanFeatures> loans = golden.get("cases").valueStream()
                .map(c -> MAPPER.treeToValue(c.get("loan"), LoanFeatures.class)).toList();
        double[] batch = model.calibratedProbabilities(loans.stream().map(model::featureVectorFor).toArray(float[][]::new));
        for (int i = 0; i < loans.size(); i++) {
            assertEquals(model.score(loans.get(i)).calibratedProbability(), batch[i], 1e-9, loans.get(i).loanId());
        }
    }

    /** A regenerated fixture must keep exercising what the audit found untested. */
    @Test
    void theFixtureCoversTheEdgeCases() {
        Set<String> regions = new HashSet<>();
        boolean unseen = false, noOriginationMonth = false;
        for (JsonNode c : golden.get("cases")) {
            regions.add(c.get("calibrationRegion").asString());
            JsonNode loan = c.get("loan");
            unseen |= loan.get("propertyState").asString().equals("ZZ");
            noOriginationMonth |= loan.get("originationMonth").isNull();
        }
        assertTrue(regions.containsAll(Set.of("inside", "above")), "calibration regions covered: " + regions);
        assertTrue(unseen, "an unseen category");
        assertTrue(noOriginationMonth, "a new application priced at the latest rate");
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
