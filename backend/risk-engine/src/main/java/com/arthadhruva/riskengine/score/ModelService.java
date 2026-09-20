package com.arthadhruva.riskengine.score;

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
public class ModelService extends AbstractOnnxModelService<LoanFeatures, ScoreResponse> {

    private static final boolean[] NO_BOOLEANS = new boolean[0];

    private static final List<String> NUMERIC = List.of("credit_score", "original_dti", "original_upb", "original_cltv",
            "original_ltv", "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent");
    private static final List<String> CATEGORICAL = List.of("occupancy_status", "property_type", "loan_purpose",
            "channel", "first_time_homebuyer_flag", "property_state");

    private final FeatureVectorBuilder vectorBuilder;
    private final IsotonicCalibrator calibrator;
    private final List<String> featureNames;

    public ModelService() throws OrtException, IOException {
        super();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Map<String, Integer>> mappings = mapper.readValue(
                readResource("category_mappings.json"),
                new TypeReference<Map<String, Map<String, Integer>>>() {});
        FeatureOrder featureOrder = mapper.readValue(readResource("feature_order.json"), FeatureOrder.class);
        this.featureNames = List.copyOf(featureOrder.allFeaturesInOrder());
        this.vectorBuilder = new FeatureVectorBuilder(featureOrder.allFeaturesInOrder(), NUMERIC, CATEGORICAL, List.of(), mappings);

        CalibrationBreakpoints calib = mapper.readValue(readResource("calibration.json"), CalibrationBreakpoints.class);
        this.calibrator = new IsotonicCalibrator(calib.xBreakpoints(), calib.yBreakpoints());
    }

    @Override
    protected String modelResourceName() {
        return "model.onnx";
    }

    @Override
    protected long[] tensorShape(float[] featureVector) {
        return new long[]{1, featureVector.length};
    }

    @Override
    protected double extractRawProbability(OrtSession.Result result) throws OrtException {
        float[][] probabilities = (float[][]) result.get(1).getValue();
        return probabilities[0][1]; // column 1 = P(default)
    }

    @Override
    protected double calibrate(double rawProbability) {
        return calibrator.calibrate(rawProbability);
    }

    @Override
    protected ScoreResponse buildResponse(double rawProbability, double calibratedProbability) {
        return new ScoreResponse(rawProbability, calibratedProbability);
    }

    public float[] featureVectorFor(LoanFeatures loan) {
        return buildFeatureVector(loan);
    }

    public List<String> featureNames() {
        return featureNames;
    }

    @Override
    protected float[] buildFeatureVector(LoanFeatures loan) {
        float[] numeric = {
                loan.creditScore(), loan.originalDti().floatValue(), loan.originalUpb().floatValue(),
                loan.originalCltv().floatValue(), loan.originalLtv().floatValue(), loan.originalInterestRate().floatValue(),
                loan.originalLoanTerm(), loan.numberOfBorrowers(), loan.numberOfUnits(), loan.miPercent().floatValue()
        };
        String[] categorical = {
                loan.occupancyStatus(), loan.propertyType(), loan.loanPurpose(),
                loan.channel(), loan.firstTimeHomebuyerFlag(), loan.propertyState()
        };
        return vectorBuilder.build(numeric, categorical, NO_BOOLEANS);
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
