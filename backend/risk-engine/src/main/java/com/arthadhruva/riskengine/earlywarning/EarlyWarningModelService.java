package com.arthadhruva.riskengine.earlywarning;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.arthadhruva.riskengine.ml.AbstractOnnxModelService;
import com.arthadhruva.riskengine.ml.FeatureVectorBuilder;
import com.arthadhruva.riskengine.ml.IsotonicCalibrator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
public class EarlyWarningModelService extends AbstractOnnxModelService<EarlyWarningFeatures, EarlyWarningResponse> {

    private static final List<String> NUMERIC = List.of("credit_score", "original_dti", "original_upb", "original_cltv",
            "original_ltv", "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units",
            "mi_percent", "loan_age", "eltv", "current_interest_rate", "upb_paydown_ratio", "rate_lock_severity",
            "eltv_change_3m", "upb_paydown_change_3m", "rate_lock_severity_change_3m",
            "eltv_change_6m", "upb_paydown_change_6m", "rate_lock_severity_change_6m");
    private static final List<String> CATEGORICAL = List.of("occupancy_status", "property_type", "loan_purpose",
            "channel", "first_time_homebuyer_flag", "property_state", "hmm_regime");
    private static final List<String> BOOLEAN = List.of("prior_assistance", "prior_modification", "prior_disaster", "upb_stalled");

    private final FeatureVectorBuilder vectorBuilder;
    private final IsotonicCalibrator calibrator;

    public EarlyWarningModelService() throws OrtException, IOException {
        super();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Map<String, Integer>> mappings = mapper.readValue(
                readResource("early_warning_category_mappings.json"),
                new TypeReference<Map<String, Map<String, Integer>>>() {});
        FeatureOrder featureOrder = mapper.readValue(readResource("early_warning_feature_order.json"), FeatureOrder.class);
        this.vectorBuilder = new FeatureVectorBuilder(featureOrder.allFeaturesInOrder(), NUMERIC, CATEGORICAL, BOOLEAN, mappings);

        CalibrationBreakpoints calib = mapper.readValue(readResource("early_warning_calibration.json"), CalibrationBreakpoints.class);
        this.calibrator = new IsotonicCalibrator(calib.xBreakpoints(), calib.yBreakpoints());
    }

    @Override
    protected String modelResourceName() {
        return "early_warning_model.onnx";
    }

    @Override
    protected long[] tensorShape(float[] featureVector) {
        return new long[]{1, featureVector.length};
    }

    @Override
    protected double calibrate(double rawProbability) {
        return calibrator.calibrate(rawProbability);
    }

    @Override
    protected EarlyWarningResponse buildResponse(double rawProbability, double calibratedProbability) {
        return new EarlyWarningResponse(rawProbability, calibratedProbability);
    }

    /**
     * onnxmltools' LightGBM classifier converter emits the probability output as a ZipMap (a
     * one-element {@code List<OnnxMap>} of class-label -> probability, keyed by the int64 class
     * label) rather than a dense tensor -- confirmed empirically against this exported model
     * (unlike the assumption baked into {@link com.arthadhruva.riskengine.score.ModelService},
     * a dense {@code float[][]} is handled too in case a future re-export changes that).
     */
    @Override
    @SuppressWarnings("unchecked")
    protected double extractRawProbability(OrtSession.Result result) throws OrtException {
        Object onnxOutput = result.get(1).getValue();
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

    @Override
    protected float[] buildFeatureVector(EarlyWarningFeatures loan) {
        float[] numeric = {
                loan.creditScore(), loan.originalDti().floatValue(), loan.originalUpb().floatValue(),
                loan.originalCltv().floatValue(), loan.originalLtv().floatValue(), loan.originalInterestRate().floatValue(),
                loan.originalLoanTerm(), loan.numberOfBorrowers(), loan.numberOfUnits(), loan.miPercent().floatValue(),
                loan.loanAge(), loan.eltv().floatValue(), loan.currentInterestRate().floatValue(),
                loan.upbPaydownRatio().floatValue(), loan.rateLockSeverity().floatValue(),
                loan.eltvChange3m().floatValue(), loan.upbPaydownChange3m().floatValue(), loan.rateLockSeverityChange3m().floatValue(),
                loan.eltvChange6m().floatValue(), loan.upbPaydownChange6m().floatValue(), loan.rateLockSeverityChange6m().floatValue()
        };
        String[] categorical = {
                loan.occupancyStatus(), loan.propertyType(), loan.loanPurpose(), loan.channel(),
                loan.firstTimeHomebuyerFlag(), loan.propertyState(), loan.hmmRegime()
        };
        boolean[] booleans = {loan.priorAssistance(), loan.priorModification(), loan.priorDisaster(), loan.upbStalled()};
        return vectorBuilder.build(numeric, categorical, booleans);
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
