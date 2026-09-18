package com.arthadhruva.riskengine.earlywarning;

import ai.onnxruntime.*;
import com.arthadhruva.riskengine.score.CategoryEncoder;
import com.arthadhruva.riskengine.score.IsotonicCalibrator;
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
 * Loads the early-warning delinquency LightGBM model (exported to ONNX by
 * backend/export_early_warning_model.py) directly into this process via ONNX Runtime, same
 * in-process-serving reasoning as {@link com.arthadhruva.riskengine.score.ModelService}.
 *
 * Applies the same isotonic-calibration correction pattern as the PD model: the raw output is
 * trained on a 10:1 downsampled target rate and overpredicts real-world risk by roughly 8x on
 * average (see early_warning_delinquency.ipynb's calibration check) -- only the calibrated
 * probability should be shown as a dollar/decision-meaningful number.
 */
@Service
public class EarlyWarningModelService {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final CategoryEncoder categoryEncoder;
    private final IsotonicCalibrator calibrator;
    private final List<String> numericFeatures;
    private final List<String> categoricalFeatures;
    private final List<String> booleanFeatures;
    private final List<String> allFeaturesInOrder;

    public EarlyWarningModelService() throws OrtException, IOException {
        this.environment = OrtEnvironment.getEnvironment();

        byte[] modelBytes = readResource("early_warning_model.onnx");
        this.session = environment.createSession(modelBytes, new OrtSession.SessionOptions());

        ObjectMapper mapper = new ObjectMapper();

        Map<String, Map<String, Integer>> mappings = mapper.readValue(
                readResource("early_warning_category_mappings.json"),
                new TypeReference<Map<String, Map<String, Integer>>>() {});
        this.categoryEncoder = new CategoryEncoder(mappings);

        FeatureOrder featureOrder = mapper.readValue(readResource("early_warning_feature_order.json"), FeatureOrder.class);
        this.numericFeatures = featureOrder.numericFeatures();
        this.categoricalFeatures = featureOrder.categoricalFeatures();
        this.booleanFeatures = featureOrder.booleanFeatures();
        this.allFeaturesInOrder = featureOrder.allFeaturesInOrder();

        CalibrationBreakpoints calib = mapper.readValue(readResource("early_warning_calibration.json"), CalibrationBreakpoints.class);
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

    public EarlyWarningResponse score(EarlyWarningFeatures loan) {
        float[] featureVector = buildFeatureVector(loan);
        try {
            OnnxTensor input = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(featureVector), new long[]{1, featureVector.length});
            try (OrtSession.Result result = session.run(Collections.singletonMap("input", input))) {
                double rawRisk = extractPositiveClassProbability(result.get(1).getValue());
                double calibratedRisk = calibrator.calibrate(rawRisk);
                return new EarlyWarningResponse(rawRisk, calibratedRisk);
            } finally {
                input.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    /**
     * onnxmltools' LightGBM classifier converter emits the probability output as a ZipMap (a
     * one-element {@code List<OnnxMap>} of class-label -> probability, keyed by the int64 class
     * label) rather than a dense tensor -- confirmed empirically against this exported model
     * (unlike the assumption baked into {@link com.arthadhruva.riskengine.score.ModelService},
     * a dense {@code float[][]} is handled too in case a future re-export changes that).
     */
    @SuppressWarnings("unchecked")
    private double extractPositiveClassProbability(Object onnxOutput) throws OrtException {
        if (onnxOutput instanceof float[][] dense) {
            return dense[0][1];
        }
        if (onnxOutput instanceof List<?> maps && !maps.isEmpty() && maps.get(0) instanceof OnnxMap onnxMap) {
            Map<?, ?> probabilityByClass = onnxMap.getValue();
            Number probability = (Number) probabilityByClass.get(1L);
            if (probability == null) {
                throw new IllegalStateException("ONNX ZipMap output has no entry for class label 1");
            }
            return probability.doubleValue();
        }
        throw new IllegalStateException("Unrecognized ONNX probability output type: "
                + (onnxOutput == null ? "null" : onnxOutput.getClass()));
    }

    private float[] buildFeatureVector(EarlyWarningFeatures loan) {
        Map<String, Float> numericValues = new HashMap<>();
        numericValues.put("credit_score", loan.creditScore().floatValue());
        numericValues.put("original_dti", loan.originalDti().floatValue());
        numericValues.put("original_upb", loan.originalUpb().floatValue());
        numericValues.put("original_cltv", loan.originalCltv().floatValue());
        numericValues.put("original_ltv", loan.originalLtv().floatValue());
        numericValues.put("original_interest_rate", loan.originalInterestRate().floatValue());
        numericValues.put("original_loan_term", loan.originalLoanTerm().floatValue());
        numericValues.put("number_of_borrowers", loan.numberOfBorrowers().floatValue());
        numericValues.put("number_of_units", loan.numberOfUnits().floatValue());
        numericValues.put("mi_percent", loan.miPercent().floatValue());
        numericValues.put("loan_age", loan.loanAge().floatValue());
        numericValues.put("eltv", loan.eltv().floatValue());
        numericValues.put("current_interest_rate", loan.currentInterestRate().floatValue());
        numericValues.put("upb_paydown_ratio", loan.upbPaydownRatio().floatValue());
        numericValues.put("rate_lock_severity", loan.rateLockSeverity().floatValue());
        numericValues.put("eltv_change_3m", loan.eltvChange3m().floatValue());
        numericValues.put("upb_paydown_change_3m", loan.upbPaydownChange3m().floatValue());
        numericValues.put("rate_lock_severity_change_3m", loan.rateLockSeverityChange3m().floatValue());
        numericValues.put("eltv_change_6m", loan.eltvChange6m().floatValue());
        numericValues.put("upb_paydown_change_6m", loan.upbPaydownChange6m().floatValue());
        numericValues.put("rate_lock_severity_change_6m", loan.rateLockSeverityChange6m().floatValue());

        Map<String, String> categoricalValues = new HashMap<>();
        categoricalValues.put("occupancy_status", loan.occupancyStatus());
        categoricalValues.put("property_type", loan.propertyType());
        categoricalValues.put("loan_purpose", loan.loanPurpose());
        categoricalValues.put("channel", loan.channel());
        categoricalValues.put("first_time_homebuyer_flag", loan.firstTimeHomebuyerFlag());
        categoricalValues.put("property_state", loan.propertyState());
        categoricalValues.put("hmm_regime", loan.hmmRegime());

        Map<String, Boolean> booleanValues = new HashMap<>();
        booleanValues.put("prior_assistance", loan.priorAssistance());
        booleanValues.put("prior_modification", loan.priorModification());
        booleanValues.put("prior_disaster", loan.priorDisaster());
        booleanValues.put("upb_stalled", loan.upbStalled());

        float[] vector = new float[allFeaturesInOrder.size()];
        for (int i = 0; i < allFeaturesInOrder.size(); i++) {
            String feature = allFeaturesInOrder.get(i);
            if (numericFeatures.contains(feature)) {
                vector[i] = numericValues.get(feature);
            } else if (categoricalFeatures.contains(feature)) {
                vector[i] = categoryEncoder.encode(feature, categoricalValues.get(feature));
            } else if (booleanFeatures.contains(feature)) {
                vector[i] = booleanValues.get(feature) ? 1.0f : 0.0f;
            } else {
                throw new IllegalStateException("Feature not classified as numeric/categorical/boolean: " + feature);
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
            @JsonProperty("boolean_features") List<String> booleanFeatures,
            @JsonProperty("all_features_in_order") List<String> allFeaturesInOrder
    ) {
    }

    private record CalibrationBreakpoints(
            @JsonProperty("x_breakpoints") double[] xBreakpoints,
            @JsonProperty("y_breakpoints") double[] yBreakpoints
    ) {
    }
}
