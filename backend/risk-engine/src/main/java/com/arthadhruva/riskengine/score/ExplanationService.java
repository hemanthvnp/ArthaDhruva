package com.arthadhruva.riskengine.score;

import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Shapley-value attribution of a loan's calibrated PD, and adverse-action reason codes derived from it.
 *
 * <p><b>Method.</b> Baseline Shapley values against a reference loan -- the training population's median
 * (numeric) and most common value (categorical), exported with the model. For each sampled ordering of the
 * features, starting from the reference and switching features to this loan's values one at a time, each
 * feature is credited with the change in PD at its step. Averaged over orderings this estimates the Shapley
 * value; within every single ordering the credits sum EXACTLY to PD(loan) - PD(reference), so the
 * attribution is always complete. Orderings are sampled in antithetic pairs (an ordering and its reverse),
 * which cancels much of the sampling noise, and seeded from the loan itself, so a loan always gets the
 * same explanation. When at most {@value #EXACT_MAX_FEATURES} features differ from the reference, the
 * exact Shapley value is computed over all coalitions instead.
 *
 * <p><b>Cost.</b> Every coalition is evaluated in ONE batched model call ({@value #PERMUTATIONS} orderings x
 * the differing features, at most ~1,000 rows), a few milliseconds -- versus one call per probe before.
 *
 * <p><b>Reason codes.</b> The features that push this loan's risk up the most (positive contributions),
 * at most four, phrased as adverse-action reasons with the loan's value against the typical one. A factor
 * is a reason only if it matters: it must add at least a hundredth of a percentage point of PD and at
 * least a twentieth of what the principal reason adds, so a loan is never told that a rounding error
 * counted against it.
 */
@Service
public class ExplanationService {

    static final int PERMUTATIONS = 64;
    static final int EXACT_MAX_FEATURES = 10;
    private static final int MAX_REASONS = 4;
    private static final double MIN_REASON_CONTRIBUTION = 1e-4;
    private static final double MIN_REASON_SHARE = 0.05;

    private final ModelService model;
    private final float[] baseline;
    private final double baselinePd;
    private final Map<String, Object> baselineValues;

    public record Explanation(List<ScoreResponse.Attribution> contributions, List<ScoreResponse.ReasonCode> reasonCodes,
                              double baselineProbability) {
    }

    public ExplanationService(ModelService model) throws IOException {
        this.model = model;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("explanation_baseline.json")) {
            if (is == null) {
                throw new IOException("explanation_baseline.json not found on classpath");
            }
            this.baselineValues = new ObjectMapper().readValue(is.readAllBytes(), new TypeReference<Map<String, Object>>() {});
        }
        this.baseline = model.vectorFromModelValues(baselineValues);
        this.baselinePd = model.calibratedProbabilities(new float[][]{baseline})[0];
    }

    public Explanation explain(LoanFeatures loan) {
        float[] x = model.featureVectorFor(loan);
        List<String> names = model.featureNames();
        int[] differing = java.util.stream.IntStream.range(0, x.length).filter(i -> x[i] != baseline[i]).toArray();
        double[] phi = differing.length <= EXACT_MAX_FEATURES ? exact(x, differing) : sampled(x, differing);

        List<ScoreResponse.Attribution> contributions = new ArrayList<>();
        for (int k = 0; k < differing.length; k++) {
            contributions.add(new ScoreResponse.Attribution(names.get(differing[k]), phi[k]));
        }
        contributions.sort(Comparator.comparingDouble((ScoreResponse.Attribution a) -> Math.abs(a.contribution())).reversed());
        return new Explanation(contributions, reasonCodes(loan, contributions), baselinePd);
    }

    /** Exact Shapley over all 2^D coalitions of the differing features. */
    private double[] exact(float[] x, int[] differing) {
        int d = differing.length;
        int coalitions = 1 << d;
        float[][] rows = new float[coalitions][];
        for (int mask = 0; mask < coalitions; mask++) {
            float[] row = baseline.clone();
            for (int k = 0; k < d; k++) {
                if ((mask & (1 << k)) != 0) {
                    row[differing[k]] = x[differing[k]];
                }
            }
            rows[mask] = row;
        }
        double[] f = model.calibratedProbabilities(rows);
        double[] weight = new double[d];
        for (int s = 0; s < d; s++) {
            weight[s] = factorial(s) * factorial(d - s - 1) / factorial(d);
        }
        double[] phi = new double[d];
        for (int mask = 0; mask < coalitions; mask++) {
            int size = Integer.bitCount(mask);
            for (int k = 0; k < d; k++) {
                if ((mask & (1 << k)) == 0) {
                    phi[k] += weight[size] * (f[mask | (1 << k)] - f[mask]);
                }
            }
        }
        return phi;
    }

    /** Antithetic permutation sampling: each ordering and its reverse, all rows in one batch. */
    private double[] sampled(float[] x, int[] differing) {
        int d = differing.length;
        SplittableRandom random = new SplittableRandom(Arrays.hashCode(x));
        int[][] orders = new int[PERMUTATIONS][];
        for (int p = 0; p < PERMUTATIONS; p += 2) {
            int[] order = java.util.stream.IntStream.range(0, d).toArray();
            for (int i = d - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
            orders[p] = order;
            int[] reversed = new int[d];
            for (int i = 0; i < d; i++) {
                reversed[i] = order[d - 1 - i];
            }
            orders[p + 1] = reversed;
        }
        float[][] rows = new float[PERMUTATIONS * d][];
        for (int p = 0; p < PERMUTATIONS; p++) {
            float[] row = baseline.clone();
            for (int step = 0; step < d; step++) {
                int feature = differing[orders[p][step]];
                row[feature] = x[feature];
                rows[p * d + step] = row.clone();
            }
        }
        double[] f = model.calibratedProbabilities(rows);
        double[] phi = new double[d];
        for (int p = 0; p < PERMUTATIONS; p++) {
            double previous = baselinePd;
            for (int step = 0; step < d; step++) {
                double current = f[p * d + step];
                phi[orders[p][step]] += current - previous;
                previous = current;
            }
        }
        for (int k = 0; k < d; k++) {
            phi[k] /= PERMUTATIONS;
        }
        return phi;
    }

    private List<ScoreResponse.ReasonCode> reasonCodes(LoanFeatures loan, List<ScoreResponse.Attribution> contributions) {
        List<ScoreResponse.ReasonCode> reasons = new ArrayList<>();
        boolean ltvReported = false;
        double principal = contributions.stream().mapToDouble(ScoreResponse.Attribution::contribution).max().orElse(0);
        double floor = Math.max(MIN_REASON_CONTRIBUTION, MIN_REASON_SHARE * principal);
        for (ScoreResponse.Attribution a : contributions.stream()
                .filter(c -> c.contribution() >= floor)
                .sorted(Comparator.comparingDouble(ScoreResponse.Attribution::contribution).reversed()).toList()) {
            if (a.feature().equals("original_ltv") || a.feature().equals("original_cltv")) {
                if (ltvReported) {
                    continue; // LTV and CLTV are one reason to a borrower
                }
                ltvReported = true;
            }
            reasons.add(new ScoreResponse.ReasonCode(code(a.feature()), a.feature(), describe(a.feature(), loan), a.contribution()));
            if (reasons.size() == MAX_REASONS) {
                break;
            }
        }
        return reasons;
    }

    private static String code(String feature) {
        return switch (feature) {
            case "credit_score" -> "R01";
            case "original_dti" -> "R02";
            case "original_ltv", "original_cltv" -> "R03";
            case "rate_spread" -> "R04";
            case "number_of_borrowers" -> "R05";
            case "occupancy_status" -> "R06";
            case "loan_purpose" -> "R07";
            case "property_type", "number_of_units" -> "R08";
            case "mi_percent" -> "R09";
            case "original_upb" -> "R10";
            case "original_loan_term" -> "R11";
            case "channel" -> "R12";
            case "first_time_homebuyer_flag" -> "R13";
            default -> "R14";
        };
    }

    private String describe(String feature, LoanFeatures loan) {
        Object typical = baselineValues.get(feature);
        return switch (feature) {
            case "credit_score" -> "Credit score of " + loan.creditScore() + " is below that of typical borrowers (" + number(typical) + ")";
            case "original_dti" -> "Debt-to-income ratio of " + number(loan.originalDti()) + "% is above typical (" + number(typical) + "%)";
            case "original_ltv", "original_cltv" -> "Loan-to-value ratio of " + number(loan.originalLtv()) + "% (combined "
                    + number(loan.originalCltv()) + "%) is above typical (" + number(baselineValues.get("original_ltv")) + "%)";
            case "rate_spread" -> String.format(Locale.ROOT, "Note rate is %.2f points above the market rate at lock, indicating elevated risk",
                    model.rateSpread(loan));
            case "number_of_borrowers" -> "Single borrower on the loan (no co-borrower income)";
            case "occupancy_status" -> "Occupancy: " + named(loan.occupancyStatus(), OCCUPANCY);
            case "loan_purpose" -> "Loan purpose: " + named(loan.loanPurpose(), PURPOSE);
            case "property_type" -> "Property type: " + named(loan.propertyType(), PROPERTY);
            case "number_of_units" -> "Property with " + loan.numberOfUnits() + " units";
            case "mi_percent" -> "Mortgage insurance coverage of " + number(loan.miPercent()) + "%";
            case "original_upb" -> "Loan amount of $" + number(loan.originalUpb());
            case "original_loan_term" -> "Loan term of " + loan.originalLoanTerm() + " months";
            case "channel" -> "Origination channel: " + named(loan.channel(), CHANNEL);
            case "first_time_homebuyer_flag" -> "First-time homebuyer";
            case "property_state" -> "Property location (state-level market conditions)";
            default -> feature;
        };
    }

    // What the category codes mean, in the words a notice to a borrower would use.
    private static final Map<String, String> OCCUPANCY = Map.of("P", "primary residence", "S", "second home", "I", "investment property");
    private static final Map<String, String> PURPOSE = Map.of("P", "purchase", "C", "cash-out refinance", "N", "refinance without cash out");
    private static final Map<String, String> PROPERTY = Map.of("SF", "single-family home", "CO", "condominium",
            "PU", "planned unit development", "MH", "manufactured home", "CP", "co-operative");
    private static final Map<String, String> CHANNEL = Map.of("R", "retail", "B", "broker", "C", "correspondent");

    private static String named(String code, Map<String, String> names) {
        return names.getOrDefault(code, code);
    }

    private static String number(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            // Only route through the integer format within a range the long cast can represent exactly;
            // a loan-derived value near that bound falls back to the decimal format instead of truncating.
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.format(Locale.ROOT, "%,d", (long) d);
            }
            return String.format(Locale.ROOT, "%.1f", d);
        }
        return String.valueOf(value);
    }

    private static double factorial(int n) {
        double f = 1;
        for (int i = 2; i <= n; i++) {
            f *= i;
        }
        return f;
    }
}
