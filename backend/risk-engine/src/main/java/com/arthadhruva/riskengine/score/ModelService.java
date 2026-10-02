package com.arthadhruva.riskengine.score;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.arthadhruva.riskengine.ml.AbstractOnnxModelService;
import com.arthadhruva.riskengine.ml.FeatureVectorBuilder;
import com.arthadhruva.riskengine.ml.IsotonicCalibrator;
import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.ml.Quantizer;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * The origination PD model: probability of default (90+ days past due, REO, or adverse termination)
 * within 24 months of origination. LightGBM with monotone constraints, served in-process through ONNX
 * Runtime and corrected by a binned isotonic calibration (backend/export_model.py; see pd_model_card.json
 * for its out-of-time validation).
 *
 * <p>The note rate enters as its spread over the Freddie Mac PMMS 30-year rate at the rate-lock month
 * ({@link MarketData}): a raw rate mixes borrower risk with the rate cycle. Every continuous input is
 * rounded to the training grid ({@link Quantizer}) so this service, the exported graph and LightGBM agree
 * to floating-point precision.
 */
@Service
public class ModelService extends AbstractOnnxModelService<LoanFeatures, ScoreResponse> {

    private static final boolean[] NO_BOOLEANS = new boolean[0];

    /** Caller-side order of the numeric array built in {@link #buildFeatureVector}. */
    private static final List<String> NUMERIC = List.of("credit_score", "original_dti", "original_upb", "original_cltv",
            "original_ltv", "rate_spread", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent");
    private static final List<String> CATEGORICAL = List.of("occupancy_status", "property_type", "loan_purpose",
            "channel", "first_time_homebuyer_flag", "property_state");

    private final FeatureVectorBuilder vectorBuilder;
    private final IsotonicCalibrator calibrator;
    private final List<String> featureNames;
    private final Quantizer quantizer;
    private final MarketData market;
    private final int rateLockLagMonths;
    private final String version;
    private final int horizonMonths;
    private final String artifactSha256;

    public ModelService(MarketData market) throws OrtException, IOException {
        super();
        this.market = market;
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String, Integer>> mappings = mapper.readValue(readResource("category_mappings.json"),
                new TypeReference<Map<String, Map<String, Integer>>>() {});
        FeatureOrder order = mapper.readValue(readResource("feature_order.json"), FeatureOrder.class);
        this.featureNames = List.copyOf(order.allFeaturesInOrder());
        this.vectorBuilder = new FeatureVectorBuilder(order.allFeaturesInOrder(), NUMERIC, CATEGORICAL, List.of(), mappings);
        this.quantizer = new Quantizer(order.allFeaturesInOrder(), order.featureDecimals());
        this.rateLockLagMonths = order.rateLockLagMonths();

        CalibrationBreakpoints calib = mapper.readValue(readResource("calibration.json"), CalibrationBreakpoints.class);
        this.calibrator = new IsotonicCalibrator(calib.xBreakpoints(), calib.yBreakpoints());

        JsonNode card = mapper.readTree(readResource("pd_model_card.json"));
        this.version = "pd_24m@" + card.get("version").asString();
        this.horizonMonths = card.get("horizon_months").asInt();
        this.artifactSha256 = card.get("artifact_sha256").asString();
    }

    public String version() {
        return version;
    }

    public int horizonMonths() {
        return horizonMonths;
    }

    /** The checksum the model card declares for model.onnx (verified at startup by ModelRegistry). */
    public String declaredArtifactSha256() {
        return artifactSha256;
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

    /** Note rate minus the PMMS rate when it was locked: {@code rateLockLagMonths} before origination, or
     * today's latest rate for a new application. */
    public double rateSpread(LoanFeatures loan) {
        YearMonth lock = loan.originationMonth() == null
                ? market.rateAsOf()
                : YearMonth.parse(loan.originationMonth()).minusMonths(rateLockLagMonths);
        return loan.originalInterestRate() - market.mortgageRate(lock);
    }

    @Override
    protected float[] buildFeatureVector(LoanFeatures loan) {
        float[] numeric = {
                loan.creditScore(), loan.originalDti().floatValue(), loan.originalUpb().floatValue(),
                loan.originalCltv().floatValue(), loan.originalLtv().floatValue(),
                // rounded from its double value (not after a float cast), exactly like training
                Quantizer.round(rateSpread(loan), 3),
                loan.originalLoanTerm(), loan.numberOfBorrowers(), loan.numberOfUnits(), loan.miPercent().floatValue()
        };
        String[] categorical = {
                loan.occupancyStatus(), loan.propertyType(), loan.loanPurpose(),
                loan.channel(), loan.firstTimeHomebuyerFlag(), loan.propertyState()
        };
        return quantizer.apply(vectorBuilder.build(numeric, categorical, NO_BOOLEANS));
    }

    /** Builds a model-space vector from explicit values (used for the explanation baseline). */
    float[] vectorFromModelValues(Map<String, Object> values) {
        float[] numeric = new float[NUMERIC.size()];
        for (int i = 0; i < NUMERIC.size(); i++) {
            numeric[i] = ((Number) values.get(NUMERIC.get(i))).floatValue();
        }
        String[] categorical = new String[CATEGORICAL.size()];
        for (int i = 0; i < CATEGORICAL.size(); i++) {
            categorical[i] = String.valueOf(values.get(CATEGORICAL.get(i)));
        }
        return quantizer.apply(vectorBuilder.build(numeric, categorical, NO_BOOLEANS));
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record FeatureOrder(
            @JsonProperty("numeric_features") List<String> numericFeatures,
            @JsonProperty("categorical_features") List<String> categoricalFeatures,
            @JsonProperty("all_features_in_order") List<String> allFeaturesInOrder,
            @JsonProperty("feature_decimals") Map<String, Integer> featureDecimals,
            @JsonProperty("rate_lock_lag_months") int rateLockLagMonths
    ) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record CalibrationBreakpoints(
            @JsonProperty("x_breakpoints") double[] xBreakpoints,
            @JsonProperty("y_breakpoints") double[] yBreakpoints
    ) {
    }
}
