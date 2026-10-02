package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.ModelService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Population stability: is the portfolio being scored still the kind of portfolio the PD model was
 * trained on? For every model input, and for the score itself, the share of loans in each bin is compared
 * with the training share (exported with the model, {@code drift_reference.json}):
 * <pre>  PSI = sum over bins of (actual - expected) * ln(actual / expected)</pre>
 * By convention below 0.10 is stable, 0.10 to 0.25 a moderate shift, above 0.25 a significant one.
 *
 * <p><b>Small samples.</b> PSI is biased upward: n loans drawn from exactly the training distribution
 * still show about (bins - 1) / n of it. A 400-loan portfolio in 10 bins carries 0.02 of pure noise, so
 * each PSI is reported with that floor and judged after subtracting it; otherwise every small book would
 * look like it was drifting.
 *
 * <p>The inputs are the model's own (the rate spread, not the raw note rate, which moves with the rate
 * cycle whether or not the borrowers have changed).
 */
@Service
public class DriftService {

    /** Floor on a bin's share: a bin that is empty on one side must not send the logarithm to infinity. */
    private static final double MIN_SHARE = 1e-4;

    public record Bin(String label, double expected, double actual) {
    }

    /**
     * @param noiseFloor the PSI a sample of this size shows with no drift at all
     * @param status     STABLE, MODERATE or SIGNIFICANT, judged on psi minus the noise floor
     */
    public record FeatureDrift(String feature, double psi, double noiseFloor, String status, int observations, List<Bin> bins) {
    }

    /** @param score drift of the calibrated PD among the loans scored so far; null when none has been scored */
    public record DriftReport(String modelVersion, int loans, List<FeatureDrift> features, FeatureDrift score, Instant computedAt) {
    }

    private record Numeric(String feature, double[] edges, double[] expected) {
    }

    private final List<Numeric> numeric = new ArrayList<>();
    private final Map<String, Map<String, Double>> categorical = new LinkedHashMap<>();
    private final Numeric scoreReference;
    private final ModelService model;
    private final Map<String, ToDoubleFunction<LoanFeatures>> numericValue = new LinkedHashMap<>();
    private final Map<String, Function<LoanFeatures, String>> categoricalValue = new LinkedHashMap<>();

    public DriftService(ModelService model) throws IOException {
        this.model = model;
        JsonNode reference;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("drift_reference.json")) {
            if (is == null) {
                throw new IOException("drift_reference.json not found on classpath");
            }
            reference = new ObjectMapper().readTree(is.readAllBytes());
        }
        for (Map.Entry<String, JsonNode> e : reference.get("numeric").properties()) {
            numeric.add(numeric(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, JsonNode> e : reference.get("categorical").properties()) {
            Map<String, Double> shares = new LinkedHashMap<>();
            e.getValue().properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(c -> shares.put(c.getKey(), c.getValue().asDouble()));
            categorical.put(e.getKey(), shares);
        }
        this.scoreReference = numeric("calibrated_pd", reference.get("score"));

        numericValue.put("credit_score", LoanFeatures::creditScore);
        numericValue.put("original_dti", LoanFeatures::originalDti);
        numericValue.put("original_upb", LoanFeatures::originalUpb);
        numericValue.put("original_cltv", LoanFeatures::originalCltv);
        numericValue.put("original_ltv", LoanFeatures::originalLtv);
        numericValue.put("rate_spread", model::rateSpread);
        numericValue.put("original_loan_term", LoanFeatures::originalLoanTerm);
        numericValue.put("number_of_borrowers", LoanFeatures::numberOfBorrowers);
        numericValue.put("number_of_units", LoanFeatures::numberOfUnits);
        numericValue.put("mi_percent", LoanFeatures::miPercent);
        categoricalValue.put("occupancy_status", LoanFeatures::occupancyStatus);
        categoricalValue.put("property_type", LoanFeatures::propertyType);
        categoricalValue.put("loan_purpose", LoanFeatures::loanPurpose);
        categoricalValue.put("channel", LoanFeatures::channel);
        categoricalValue.put("first_time_homebuyer_flag", LoanFeatures::firstTimeHomebuyerFlag);
        categoricalValue.put("property_state", LoanFeatures::propertyState);
        for (Numeric n : numeric) {
            if (!numericValue.containsKey(n.feature())) {
                throw new IllegalStateException("drift_reference.json has an unknown numeric feature: " + n.feature());
            }
        }
    }

    private static Numeric numeric(String feature, JsonNode node) {
        double[] edges = new double[node.get("edges").size()];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = node.get("edges").get(i).asDouble();
        }
        double[] expected = new double[node.get("proportions").size()];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = node.get("proportions").get(i).asDouble();
        }
        if (expected.length != edges.length + 1) {
            throw new IllegalStateException("drift reference for " + feature + " has " + edges.length + " edges but "
                    + expected.length + " bins");
        }
        return new Numeric(feature, edges, expected);
    }

    /**
     * @param loans  the portfolio (normalized loans)
     * @param scores calibrated PDs recorded for it, possibly empty
     */
    public DriftReport report(List<LoanFeatures> loans, List<Double> scores) {
        List<FeatureDrift> features = new ArrayList<>();
        for (Numeric reference : numeric) {
            ToDoubleFunction<LoanFeatures> value = numericValue.get(reference.feature());
            features.add(numericDrift(reference, loans.stream().mapToDouble(value).toArray()));
        }
        for (Map.Entry<String, Map<String, Double>> reference : categorical.entrySet()) {
            Function<LoanFeatures, String> value = categoricalValue.get(reference.getKey());
            features.add(categoricalDrift(reference.getKey(), reference.getValue(), loans.stream().map(value).toList()));
        }
        features.sort((a, b) -> Double.compare(b.psi() - b.noiseFloor(), a.psi() - a.noiseFloor()));
        FeatureDrift score = scores.isEmpty() ? null
                : numericDrift(scoreReference, scores.stream().mapToDouble(Double::doubleValue).toArray());
        return new DriftReport(model.version(), loans.size(), features, score, Instant.now());
    }

    private static FeatureDrift numericDrift(Numeric reference, double[] values) {
        double[] edges = reference.edges();
        int[] counts = new int[edges.length + 1];
        for (double v : values) {
            int bin = 0;
            while (bin < edges.length && v >= edges[bin]) {   // the training bins are [edge, next edge)
                bin++;
            }
            counts[bin]++;
        }
        List<Bin> bins = new ArrayList<>(counts.length);
        for (int b = 0; b < counts.length; b++) {
            String label = b == 0 ? "< " + number(edges[0])
                    : b == edges.length ? ">= " + number(edges[b - 1])
                    : number(edges[b - 1]) + " to " + number(edges[b]);
            bins.add(new Bin(label, reference.expected()[b], values.length == 0 ? 0 : (double) counts[b] / values.length));
        }
        return drift(reference.feature(), bins, values.length);
    }

    private static FeatureDrift categoricalDrift(String feature, Map<String, Double> expected, List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        expected.keySet().forEach(category -> counts.put(category, 0));
        int other = 0;
        for (String value : values) {
            if (counts.containsKey(value)) {
                counts.merge(value, 1, Integer::sum);
            } else {
                other++;
            }
        }
        List<Bin> bins = new ArrayList<>(counts.size() + 1);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            bins.add(new Bin(e.getKey(), expected.get(e.getKey()), values.isEmpty() ? 0 : (double) e.getValue() / values.size()));
        }
        if (other > 0) {
            bins.add(new Bin("(not in training)", 0, (double) other / values.size()));
        }
        return drift(feature, bins, values.size());
    }

    private static FeatureDrift drift(String feature, List<Bin> bins, int n) {
        double psi = 0;
        int occupied = 0;
        for (Bin bin : bins) {
            if (bin.expected() <= 0 && bin.actual() <= 0) {
                continue;   // a bin that exists only as a boundary (e.g. fewer than one borrower)
            }
            occupied++;
            double expected = Math.max(MIN_SHARE, bin.expected()), actual = Math.max(MIN_SHARE, bin.actual());
            psi += (actual - expected) * Math.log(actual / expected);
        }
        double noiseFloor = n == 0 ? 0 : Math.max(0, occupied - 1) / (double) n;
        double beyondNoise = psi - noiseFloor;
        String status = n == 0 ? "NO_DATA" : beyondNoise < 0.10 ? "STABLE" : beyondNoise < 0.25 ? "MODERATE" : "SIGNIFICANT";
        return new FeatureDrift(feature, psi, noiseFloor, status, n, bins);
    }

    private static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) >= 1) {
            return String.format(Locale.ROOT, "%,d", (long) v);
        }
        return Math.abs(v) < 1 ? String.format(Locale.ROOT, "%.4f", v) : String.format(Locale.ROOT, "%.3f", v);
    }
}
