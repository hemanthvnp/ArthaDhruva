package com.arthadhruva.riskengine.score;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.ml.Quantizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shape of the contract between the exported artifacts and the services that read them: feature_order.json,
 * category_mappings.json and the ONNX graph's input width. Complements the value-level parity tests -- these fail
 * with a message that names the mismatch rather than a probability that is slightly off.
 */
class FeatureContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ModelService model;

    @BeforeAll
    static void load() throws Exception {
        model = new ModelService(new MarketData());
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    @Test
    void pdFeatureOrderIsConsistentWithItsOwnTypeLists() throws Exception {
        JsonNode order = json("feature_order.json");
        List<String> combined = new ArrayList<>(strings(order.get("numeric_features")));
        combined.addAll(strings(order.get("categorical_features")));
        assertEquals(strings(order.get("all_features_in_order")), combined, "numeric columns, then categorical, as the trainer lays them out");
        assertEquals(combined, model.featureNames());
    }

    @Test
    void pdModelInputWidthMatchesTheFeatureOrder() throws Exception {
        assertEquals(model.featureNames().size(), onnxInputWidth("model.onnx"));
    }

    @Test
    void earlyWarningModelInputWidthMatchesItsFeatureOrder() throws Exception {
        JsonNode order = json("early_warning_feature_order.json");
        List<String> combined = new ArrayList<>(strings(order.get("numeric_features")));
        combined.addAll(strings(order.get("categorical_features")));
        combined.addAll(strings(order.get("boolean_features")));
        assertEquals(strings(order.get("all_features_in_order")), combined);
        assertEquals(combined.size(), onnxInputWidth("early_warning_model.onnx"));
    }

    /**
     * Every field of the loan lands in the column the feature order names for it. Each input has a distinct value,
     * so a swap between two same-typed fields in the service's positional arrays cannot go unnoticed.
     */
    @Test
    void eachPdInputLandsInItsNamedColumn() throws Exception {
        LoanFeatures loan = new LoanFeatures("T", 701, 37.0, 123457.0, 83.0, 81.0, 6.5, 240, 3, 2, 11.0,
                "S", "PU", "C", "B", "Y", "TX", "2022-11");
        JsonNode mappings = json("category_mappings.json");
        List<String> names = model.featureNames();
        float[] vector = model.featureVectorFor(loan);

        assertColumn(names, vector, "credit_score", 701f);
        assertColumn(names, vector, "original_dti", 37f);
        assertColumn(names, vector, "original_upb", 123457f);
        assertColumn(names, vector, "original_cltv", 83f);
        assertColumn(names, vector, "original_ltv", 81f);
        assertColumn(names, vector, "rate_spread", Quantizer.round(model.rateSpread(loan), 3));
        assertColumn(names, vector, "original_loan_term", 240f);
        assertColumn(names, vector, "number_of_borrowers", 3f);
        assertColumn(names, vector, "number_of_units", 2f);
        assertColumn(names, vector, "mi_percent", 11f);
        assertColumn(names, vector, "occupancy_status", code(mappings, "occupancy_status", "S"));
        assertColumn(names, vector, "property_type", code(mappings, "property_type", "PU"));
        assertColumn(names, vector, "loan_purpose", code(mappings, "loan_purpose", "C"));
        assertColumn(names, vector, "channel", code(mappings, "channel", "B"));
        assertColumn(names, vector, "first_time_homebuyer_flag", code(mappings, "first_time_homebuyer_flag", "Y"));
        assertColumn(names, vector, "property_state", code(mappings, "property_state", "TX"));
        assertEquals(names.size(), vector.length);
    }

    private static void assertColumn(List<String> names, float[] vector, String feature, float expected) {
        int column = names.indexOf(feature);
        assertEquals(expected, vector[column], 0.0f, feature + " (column " + column + ")");
    }

    private static float code(JsonNode mappings, String feature, String value) {
        return (float) mappings.get(feature).get(value).asInt();
    }

    private static long onnxInputWidth(String resource) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession(bytes(resource), new OrtSession.SessionOptions())) {
            NodeInfo input = session.getInputInfo().get("input");
            long[] shape = ((TensorInfo) input.getInfo()).getShape();
            return shape[shape.length - 1];
        }
    }

    private static JsonNode json(String resource) throws Exception {
        return MAPPER.readTree(bytes(resource));
    }

    private static byte[] bytes(String resource) throws Exception {
        try (InputStream is = FeatureContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            return is.readAllBytes();
        }
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
