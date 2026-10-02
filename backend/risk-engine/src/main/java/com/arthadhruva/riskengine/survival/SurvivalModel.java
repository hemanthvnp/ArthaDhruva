package com.arthadhruva.riskengine.survival;

import ai.onnxruntime.OrtException;
import com.arthadhruva.riskengine.ml.FeatureVectorBuilder;
import com.arthadhruva.riskengine.ml.OnnxModel;
import com.arthadhruva.riskengine.ml.Quantizer;
import com.arthadhruva.riskengine.score.LoanFeatures;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The competing-risks survival model (backend/export_survival_model.py): for one loan-month at risk,
 * the probabilities of defaulting, prepaying, or continuing, given the loan's origination attributes and
 * that month's loan age, macro regime, rate incentive, state unemployment (level and 12-month change),
 * state house-price change and mark-to-market LTV.
 *
 * <p>Rows are built cheaply: the loan's static part is encoded once, and each (month, regime) row copies
 * it and sets only the time-varying columns, rounded to the training grid ({@link Quantizer}).
 * A whole term structure is then one {@link OnnxModel} call.
 *
 * <p>The drift overlay (realized over predicted hazard on held-out loans over the latest 24 months, per
 * cause, applied only where the training script found it both significant and validated out-of-time; see
 * the model metadata) is applied to the hazards here, so every consumer uses the same adjusted model.
 */
@Component
public class SurvivalModel {

    static final int CONTINUE = 0;
    static final int DEFAULT = 1;
    static final int PREPAY = 2;

    /** Regime indices, as in the metadata's {@code regimes.labels}. */
    public static final int CALM = 0;
    public static final int STRESSED = 1;

    /** A month always leaves some chance of continuing: the two hazards of a row never sum past this. */
    static final double MAX_EXIT_PROBABILITY = 0.999;

    private static final List<String> NUMERIC = List.of("credit_score", "original_dti", "original_upb", "original_cltv",
            "original_ltv", "rate_spread", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent",
            "loan_age", "regime_stressed", "rate_incentive", "unemployment", "unemployment_change_12m", "hpi_change_12m", "mtm_ltv");
    private static final List<String> CATEGORICAL = List.of("occupancy_status", "property_type", "loan_purpose",
            "channel", "first_time_homebuyer_flag", "property_state");
    /** The time-varying columns, in the order {@link #writeRow} sets them. */
    private static final List<String> TIME_VARYING = List.of("loan_age", "regime_stressed", "rate_incentive", "unemployment",
            "unemployment_change_12m", "hpi_change_12m", "mtm_ltv");
    private static final boolean[] NO_BOOLEANS = new boolean[0];

    /** The range a driver spans in the training rows (0.5th to 99.5th percentile). */
    public record Range(double min, double max) {
    }

    private record Period(YearMonth first, YearMonth last) {
    }

    private final OnnxModel onnx;
    private final FeatureVectorBuilder builder;
    private final Quantizer quantizer;
    private final int[] timeVaryingIndex = new int[TIME_VARYING.size()];
    private final double defaultScalar;
    private final double prepayScalar;
    private final String version;
    private final int rateLockLagMonths;
    private final double[][] transition;
    private final List<String> regimeLabels;
    private final int currentRegime;
    private final YearMonth regimeAsOf;
    private final List<Period> stressedPeriods = new ArrayList<>();
    private final Map<String, Range> envelope = new LinkedHashMap<>();
    private final JsonNode metadata;

    /** @param overlayEnabled applies the drift overlay (default on; off shows the bare model) */
    public SurvivalModel(@Value("${survival.overlay-enabled:true}") boolean overlayEnabled) throws IOException, OrtException {
        ObjectMapper mapper = new ObjectMapper();
        this.metadata = mapper.readTree(read("survival_model.json"));
        List<String> order = new ArrayList<>();
        metadata.get("features_in_order").forEach(f -> order.add(f.asString()));
        Map<String, Map<String, Integer>> mappings = mapper.readValue(read("category_mappings.json"),
                new TypeReference<Map<String, Map<String, Integer>>>() {});
        Map<String, Integer> decimals = mapper.convertValue(metadata.get("feature_decimals"), new TypeReference<Map<String, Integer>>() {});
        this.builder = new FeatureVectorBuilder(order, NUMERIC, CATEGORICAL, List.of(), mappings);
        this.quantizer = new Quantizer(order, decimals);
        for (int i = 0; i < TIME_VARYING.size(); i++) {
            timeVaryingIndex[i] = order.indexOf(TIME_VARYING.get(i));
            if (timeVaryingIndex[i] < 0) {
                throw new IllegalStateException("Survival model has no feature " + TIME_VARYING.get(i));
            }
        }
        this.onnx = OnnxModel.load("survival_model.onnx", order.size(), 1);
        if (!onnx.sha256().equals(metadata.get("artifact_sha256").asString())) {
            throw new IllegalStateException("survival_model.onnx does not match the checksum in survival_model.json");
        }
        JsonNode scalars = metadata.get("overlay").get("hazard_scalars");
        this.defaultScalar = overlayEnabled ? scalars.get("default").asDouble() : 1.0;
        this.prepayScalar = overlayEnabled ? scalars.get("prepay").asDouble() : 1.0;
        this.version = "survival@" + metadata.get("version").asString();
        this.rateLockLagMonths = metadata.get("rate_lock_lag_months").asInt();

        JsonNode regimes = metadata.get("regimes");
        this.regimeLabels = new ArrayList<>();
        regimes.get("labels").forEach(l -> regimeLabels.add(l.asString()));
        this.transition = mapper.convertValue(regimes.get("transition_matrix"), double[][].class);
        if (transition.length != 2 || transition[0].length != 2 || transition[1].length != 2) {
            throw new IllegalStateException("The survival engine expects a two-regime transition matrix");
        }
        // The fitted matrix is stored in single precision, so its rows sum to 1 only to about 1e-8. Left
        // alone, total probability would drift by that much every projected month.
        for (double[] row : transition) {
            double sum = row[0] + row[1];
            row[0] /= sum;
            row[1] /= sum;
        }
        this.currentRegime = regimes.get("current_state_index").asInt();
        this.regimeAsOf = YearMonth.parse(regimes.get("as_of_month").asString());
        regimes.get("stressed_periods").forEach(p ->
                stressedPeriods.add(new Period(YearMonth.parse(p.get(0).asString()), YearMonth.parse(p.get(1).asString()))));
        for (Map.Entry<String, JsonNode> e : metadata.get("training").get("envelope").properties()) {
            envelope.put(e.getKey(), new Range(e.getValue().get(0).asDouble(), e.getValue().get(1).asDouble()));
        }
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream is = SurvivalModel.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    public String version() {
        return version;
    }

    public String artifactSha256() {
        return onnx.sha256();
    }

    public int features() {
        return onnx.features();
    }

    public int rateLockLagMonths() {
        return rateLockLagMonths;
    }

    public double[][] transitionMatrix() {
        return transition;
    }

    public List<String> regimeLabels() {
        return regimeLabels;
    }

    public int currentRegime() {
        return currentRegime;
    }

    public YearMonth regimeAsOf() {
        return regimeAsOf;
    }

    /** Drift overlay on the default hazard (1 when none is applied). */
    public double defaultScalar() {
        return defaultScalar;
    }

    /** Drift overlay on the prepayment hazard (1 when none is applied). */
    public double prepayScalar() {
        return prepayScalar;
    }

    public JsonNode metadata() {
        return metadata;
    }

    /** Training range of a time-varying driver (see {@link Range}). */
    public Range envelope(String driver) {
        Range range = envelope.get(driver);
        if (range == null) {
            throw new IllegalArgumentException("No training envelope for " + driver);
        }
        return range;
    }

    /**
     * The regime distribution {@code [calm, stressed]} in {@code month}, as it would be assessed standing in
     * {@code knownAt}: the decoded regime of the latest month known by then, carried through the Markov
     * chain for every month since. A past month that has been decoded is a point mass.
     */
    public double[] regimeDistribution(YearMonth month, YearMonth knownAt) {
        YearMonth anchor = knownAt.isAfter(regimeAsOf) ? regimeAsOf : knownAt;
        if (month.isBefore(anchor)) {
            anchor = month;
        }
        double[] d = new double[2];
        d[decodedRegime(anchor)] = 1.0;
        long steps = anchor.until(month, ChronoUnit.MONTHS);
        for (long s = 0; s < steps; s++) {
            double calm = d[CALM] * transition[CALM][CALM] + d[STRESSED] * transition[STRESSED][CALM];
            d[STRESSED] = d[CALM] * transition[CALM][STRESSED] + d[STRESSED] * transition[STRESSED][STRESSED];
            d[CALM] = calm;
        }
        return d;
    }

    /** The regime decoded for a month up to {@link #regimeAsOf()} (calm before the regime model's history). */
    int decodedRegime(YearMonth month) {
        if (month.equals(regimeAsOf)) {
            return currentRegime;
        }
        for (Period p : stressedPeriods) {
            if (!month.isBefore(p.first()) && !month.isAfter(p.last())) {
                return STRESSED;
            }
        }
        return CALM;
    }

    /** The loan's encoded static part (time-varying columns zero) -- the template every row copies. */
    float[] template(LoanFeatures loan, double rateSpread) {
        float[] numeric = {
                loan.creditScore(), loan.originalDti().floatValue(), loan.originalUpb().floatValue(),
                loan.originalCltv().floatValue(), loan.originalLtv().floatValue(), Quantizer.round(rateSpread, 3),
                loan.originalLoanTerm(), loan.numberOfBorrowers(), loan.numberOfUnits(), loan.miPercent().floatValue(),
                0, 0, 0, 0, 0, 0, 0
        };
        String[] categorical = {loan.occupancyStatus(), loan.propertyType(), loan.loanPurpose(), loan.channel(),
                loan.firstTimeHomebuyerFlag(), loan.propertyState()};
        return quantizer.apply(builder.build(numeric, categorical, NO_BOOLEANS));
    }

    /** Writes one row into {@code flat} at {@code offset}: the template plus this month's time-varying values. */
    void writeRow(float[] flat, int offset, float[] template, int loanAge, int regime, double rateIncentive,
                  double unemployment, double unemploymentChange12m, double hpiChange12m, double mtmLtv) {
        System.arraycopy(template, 0, flat, offset, template.length);
        flat[offset + timeVaryingIndex[0]] = loanAge;
        flat[offset + timeVaryingIndex[1]] = regime;
        flat[offset + timeVaryingIndex[2]] = quantizer.value(timeVaryingIndex[2], rateIncentive);
        flat[offset + timeVaryingIndex[3]] = quantizer.value(timeVaryingIndex[3], unemployment);
        flat[offset + timeVaryingIndex[4]] = quantizer.value(timeVaryingIndex[4], unemploymentChange12m);
        flat[offset + timeVaryingIndex[5]] = quantizer.value(timeVaryingIndex[5], hpiChange12m);
        flat[offset + timeVaryingIndex[6]] = quantizer.value(timeVaryingIndex[6], mtmLtv);
    }

    /**
     * Monthly hazards for {@code rows} rows, overlay applied, as a flat array: the default hazard of row
     * {@code i} at {@code 2i} and its prepayment hazard at {@code 2i + 1}.
     */
    double[] hazards(float[] flat, int rows) {
        float[][] p = onnx.probabilities(flat, rows);
        double[] out = new double[2 * rows];
        for (int i = 0; i < rows; i++) {
            double hd = p[i][DEFAULT] * defaultScalar;
            double hp = p[i][PREPAY] * prepayScalar;
            double total = hd + hp;
            if (total > MAX_EXIT_PROBABILITY) {
                hd *= MAX_EXIT_PROBABILITY / total;
                hp *= MAX_EXIT_PROBABILITY / total;
            }
            out[2 * i] = hd;
            out[2 * i + 1] = hp;
        }
        return out;
    }

    @PreDestroy
    void close() throws OrtException {
        onnx.close();
    }
}
