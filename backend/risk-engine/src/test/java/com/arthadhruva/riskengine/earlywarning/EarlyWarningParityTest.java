package com.arthadhruva.riskengine.earlywarning;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column layout and category encoding of the early-warning service against an independent Python implementation
 * (backend/golden_early_warning.py), complementing {@link EarlyWarningModelServiceTest}'s four checkpoint rows:
 * each boolean flag on its own (so a swap between them shows), values the category mappings do not contain,
 * and the exact 32-column vector the service hands to ONNX. Same package as the service so the test can read
 * the vector it builds.
 */
class EarlyWarningParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode golden;
    private static EarlyWarningModelService service;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = EarlyWarningParityTest.class.getClassLoader().getResourceAsStream("early_warning_golden.json")) {
            golden = MAPPER.readTree(is.readAllBytes());
        }
        service = new EarlyWarningModelService();
    }

    @AfterAll
    static void close() throws Exception {
        service.close();
    }

    @Test
    void theGoldenFileBelongsToTheShippedModel() throws Exception {
        byte[] onnx;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("early_warning_model.onnx")) {
            onnx = is.readAllBytes();
        }
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(onnx)),
                golden.get("artifact_sha256").asString(), "regenerate with: python golden_early_warning.py");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("early_warning_feature_order.json")) {
            List<String> shipped = MAPPER.readTree(is.readAllBytes()).get("all_features_in_order").valueStream()
                    .map(JsonNode::asString).toList();
            assertEquals(shipped, golden.get("features").valueStream().map(JsonNode::asString).toList());
        }
    }

    @Test
    void javaBuildsTheSameThirtyTwoColumnsAsPython() {
        for (JsonNode c : golden.get("cases")) {
            float[] expected = new float[c.get("featureVector").size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (float) c.get("featureVector").get(i).asDouble();
            }
            assertArrayEquals(expected, service.buildFeatureVector(loan(c)), 0.0f, c.get("name").asString());
        }
    }

    @Test
    void javaReproducesPythonRawAndCalibratedRisk() {
        for (JsonNode c : golden.get("cases")) {
            EarlyWarningResponse response = service.score(loan(c));
            String name = c.get("name").asString();
            assertEquals(c.get("rawRisk").asDouble(), response.rawRisk(), 1e-6, name + ": raw");
            assertEquals(c.get("calibratedRisk").asDouble(), response.calibratedRisk(), 1e-6, name + ": calibrated");
        }
    }

    /** A value the mapping does not contain encodes as -1; the mapping's own "unknown" regime keeps its code. */
    @Test
    void unseenCategoriesEncodeAsMinusOneAndTheUnknownRegimeKeepsItsCode() {
        List<String> features = golden.get("features").valueStream().map(JsonNode::asString).toList();
        int state = features.indexOf("property_state"), regime = features.indexOf("hmm_regime");
        assertEquals(-1f, vectorOf("unseen-state")[state], 0.0f);
        assertEquals(-1f, vectorOf("unseen-regime")[regime], 0.0f);
        assertEquals(2f, vectorOf("known-unknown-regime")[regime], 0.0f);
    }

    /** A regenerated fixture must keep what the audit found untested. */
    @Test
    void theFixtureCoversEveryBooleanOnItsOwnAndUnseenValues() {
        Set<String> names = new java.util.HashSet<>();
        golden.get("cases").forEach(c -> names.add(c.get("name").asString()));
        assertTrue(names.containsAll(Set.of("prior-assistance-only", "prior-modification-only", "prior-disaster-only",
                "upb-stalled-only", "unseen-state", "unseen-regime", "unseen-everywhere")), names.toString());
    }

    private float[] vectorOf(String caseName) {
        for (JsonNode c : golden.get("cases")) {
            if (c.get("name").asString().equals(caseName)) {
                return service.buildFeatureVector(loan(c));
            }
        }
        throw new AssertionError("no case " + caseName);
    }

    private static EarlyWarningFeatures loan(JsonNode c) {
        return MAPPER.treeToValue(c.get("loan"), EarlyWarningFeatures.class);
    }
}
