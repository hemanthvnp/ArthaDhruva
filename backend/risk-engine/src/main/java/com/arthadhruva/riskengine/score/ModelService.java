package com.arthadhruva.riskengine.score;

import ai.onnxruntime.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Loads the LightGBM PD model (exported to ONNX by backend/export_model.py) directly into this
 * process via ONNX Runtime -- in-process serving, not a separate microservice call, specifically
 * to avoid a network hop per scoring request (see the architecture reasoning documented in the
 * project's reference doc: in-process ONNX vs. a Python microservice).
 *
 * Also applies the isotonic calibration correction found in default_risk_model.ipynb: the raw
 * LightGBM output overpredicts default probability by ~17.5x on average (scale_pos_weight
 * improves ranking but distorts the probabilities themselves). Only the calibrated probability
 * should be used for any dollar-valued decision.
 */
@Service
public class ModelService {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final CategoryEncoder categoryEncoder;
    private final IsotonicCalibrator calibrator;
    private final List<String> numericFeatures;
    private final List<String> categoricalFeatures;
    private final List<String> allFeaturesInOrder;

    public ModelService() throws OrtException, IOException {
        this.environment = OrtEnvironment.getEnvironment();

        byte[] modelBytes = readResource("model.onnx");
        this.session = environment.createSession(modelBytes, new OrtSession.SessionOptions());

        ObjectMapper mapper = new ObjectMapper();

        Map<String, Map<String, Integer>> mappings = mapper.readValue(
                readResource("category_mappings.json"),
                new TypeReference<Map<String, Map<String, Integer>>>() {});
        this.categoryEncoder = new CategoryEncoder(mappings);

        FeatureOrder featureOrder = mapper.readValue(readResource("feature_order.json"), FeatureOrder.class);
        this.numericFeatures = featureOrder.numericFeatures();
        this.categoricalFeatures = featureOrder.categoricalFeatures();
        this.allFeaturesInOrder = featureOrder.allFeaturesInOrder();

        CalibrationBreakpoints calib = mapper.readValue(readResource("calibration.json"), CalibrationBreakpoints.class);
        this.calibrator = new IsotonicCalibrator(calib.xBreakpoints(), calib.yBreakpoints());
    }

    private byte[] readResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    public ScoreResponse score(LoanFeatures loan) {
        float[] featureVector = buildFeatureVector(loan);
        try {
            OnnxTensor input = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(featureVector), new long[]{1, featureVector.length});
            try (OrtSession.Result result = session.run(Collections.singletonMap("input", input))) {
                float[][] probabilities = (float[][]) result.get(1).getValue();
                double rawProbability = probabilities[0][1]; // column 1 = P(default)
                double calibratedProbability = calibrator.calibrate(rawProbability);
                return new ScoreResponse(rawProbability, calibratedProbability);
            } finally {
                input.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    private float[] buildFeatureVector(LoanFeatures loan) {
        Map<String, Float> numericValues = Map.of(
                "credit_score", loan.creditScore().floatValue(),
                "original_dti", loan.originalDti().floatValue(),
                "original_upb", loan.originalUpb().floatValue(),
                "original_cltv", loan.originalCltv().floatValue(),
                "original_ltv", loan.originalLtv().floatValue(),
                "original_interest_rate", loan.originalInterestRate().floatValue(),
                "original_loan_term", loan.originalLoanTerm().floatValue(),
                "number_of_borrowers", loan.numberOfBorrowers().floatValue(),
                "number_of_units", loan.numberOfUnits().floatValue(),
                "mi_percent", loan.miPercent().floatValue()
        );
        Map<String, String> categoricalValues = Map.of(
                "occupancy_status", loan.occupancyStatus(),
                "property_type", loan.propertyType(),
                "loan_purpose", loan.loanPurpose(),
                "channel", loan.channel(),
                "first_time_homebuyer_flag", loan.firstTimeHomebuyerFlag(),
                "property_state", loan.propertyState()
        );

        float[] vector = new float[allFeaturesInOrder.size()];
        for (int i = 0; i < allFeaturesInOrder.size(); i++) {
            String feature = allFeaturesInOrder.get(i);
            if (numericFeatures.contains(feature)) {
                vector[i] = numericValues.get(feature);
            } else {
                vector[i] = categoryEncoder.encode(feature, categoricalValues.get(feature));
            }
        }
        return vector;
    }

    @PreDestroy
    public void close() throws OrtException {
        session.close();
        environment.close();
    }

    private record FeatureOrder(
            @JsonProperty("numeric_features") List<String> numericFeatures,
            @JsonProperty("categorical_features") List<String> categoricalFeatures,
            @JsonProperty("all_features_in_order") List<String> allFeaturesInOrder
    ) {
    }

    private record CalibrationBreakpoints(
            @JsonProperty("x_breakpoints") double[] xBreakpoints,
            @JsonProperty("y_breakpoints") double[] yBreakpoints
    ) {
    }
}
