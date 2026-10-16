package com.arthadhruva.riskengine.trajectory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The trajectory service's feature layout (status parsing and sentinel, UPB-ratio clipping, standardisation,
 * the strict "Y" modification flag) and its probability against an independent Python implementation
 * (backend/golden_lstm.py). Same package as the service so the test can read the vector it builds.
 */
class TrajectoryParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode golden;
    private static TrajectoryModelService service;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = TrajectoryParityTest.class.getClassLoader().getResourceAsStream("lstm_golden.json")) {
            golden = MAPPER.readTree(is.readAllBytes());
        }
        service = new TrajectoryModelService();
    }

    @AfterAll
    static void close() throws Exception {
        service.close();
    }

    @Test
    void theGoldenFileBelongsToTheShippedModel() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("lstm_model.onnx")) {
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(is.readAllBytes())),
                    golden.get("artifact_sha256").asString(), "regenerate with: python golden_lstm.py");
        }
    }

    @Test
    void javaBuildsTheSameFeaturesAsPython() {
        for (JsonNode c : golden.get("cases")) {
            float[] expected = new float[c.get("featureVector").size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (float) c.get("featureVector").get(i).asDouble();
            }
            assertArrayEquals(expected, service.buildFeatureVector(request(c)), 1e-6f, c.get("name").asString());
        }
    }

    @Test
    void javaReproducesPythonProbability() {
        for (JsonNode c : golden.get("cases")) {
            assertEquals(c.get("probability").asDouble(), service.score(request(c)).probability(), 1e-6,
                    c.get("name").asString());
        }
    }

    private static TrajectoryRequest request(JsonNode c) {
        return MAPPER.treeToValue(c.get("loan"), TrajectoryRequest.class);
    }
}
