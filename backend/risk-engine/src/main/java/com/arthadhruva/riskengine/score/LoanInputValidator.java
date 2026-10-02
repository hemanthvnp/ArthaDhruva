package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.ml.MarketData;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Checks a loan against the domain the credit models were trained on ({@code feature_domain.json},
 * exported with the model):
 * <ul>
 *   <li><b>Rejected</b> (422): a category code the model has never seen -- previously it was silently
 *       encoded as "missing" and scored anyway -- and values outside hard physical/contractual bounds.</li>
 *   <li><b>Warned</b>: a plausible value outside the central 99% of the training data. The loan is
 *       scored, but the response says the result is an extrapolation.</li>
 * </ul>
 */
@Component
public class LoanInputValidator {

    public record Result(List<String> warnings) {
    }

    /** A loan the model cannot score, with a message per offending field. */
    public static class InvalidLoanException extends RuntimeException {
        private final transient Map<String, String> fields;

        InvalidLoanException(Map<String, String> fields) {
            super("The loan is outside what the model can score");
            this.fields = fields;
        }

        public Map<String, String> getFields() {
            return fields;
        }
    }

    private record Bound(double hardMin, double hardMax, double softMin, double softMax) {
    }

    private static final Map<String, Function<LoanFeatures, Number>> NUMERIC = new LinkedHashMap<>();
    private static final Map<String, Function<LoanFeatures, String>> CATEGORICAL = new LinkedHashMap<>();

    static {
        NUMERIC.put("credit_score", LoanFeatures::creditScore);
        NUMERIC.put("original_dti", LoanFeatures::originalDti);
        NUMERIC.put("original_upb", LoanFeatures::originalUpb);
        NUMERIC.put("original_cltv", LoanFeatures::originalCltv);
        NUMERIC.put("original_ltv", LoanFeatures::originalLtv);
        NUMERIC.put("original_interest_rate", LoanFeatures::originalInterestRate);
        NUMERIC.put("original_loan_term", LoanFeatures::originalLoanTerm);
        NUMERIC.put("number_of_borrowers", LoanFeatures::numberOfBorrowers);
        NUMERIC.put("number_of_units", LoanFeatures::numberOfUnits);
        NUMERIC.put("mi_percent", LoanFeatures::miPercent);
        CATEGORICAL.put("occupancy_status", LoanFeatures::occupancyStatus);
        CATEGORICAL.put("property_type", LoanFeatures::propertyType);
        CATEGORICAL.put("loan_purpose", LoanFeatures::loanPurpose);
        CATEGORICAL.put("channel", LoanFeatures::channel);
        CATEGORICAL.put("first_time_homebuyer_flag", LoanFeatures::firstTimeHomebuyerFlag);
        CATEGORICAL.put("property_state", LoanFeatures::propertyState);
    }

    private final Map<String, Bound> bounds = new HashMap<>();
    private final Map<String, Set<String>> vocabulary = new HashMap<>();
    private final YearMonth historyStart;

    public LoanInputValidator(MarketData market) throws IOException {
        this.historyStart = market.historyStart();
        JsonNode domain;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("feature_domain.json")) {
            if (is == null) {
                throw new IOException("feature_domain.json not found on classpath");
            }
            domain = new ObjectMapper().readTree(is.readAllBytes());
        }
        for (String feature : NUMERIC.keySet()) {
            JsonNode b = domain.get("numeric").get(feature);
            bounds.put(feature, new Bound(b.get("hard_min").asDouble(), b.get("hard_max").asDouble(),
                    b.get("soft_min").asDouble(), b.get("soft_max").asDouble()));
        }
        for (String feature : CATEGORICAL.keySet()) {
            Set<String> values = new LinkedHashSet<>();
            domain.get("categorical").get(feature).forEach(v -> values.add(v.asString()));
            vocabulary.put(feature, values);
        }
    }

    /** @param loan a {@link LoanFeatures#normalized()} loan
     * @throws InvalidLoanException if any field is outside the model's domain */
    public Result check(LoanFeatures loan) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        NUMERIC.forEach((feature, getter) -> {
            Number value = getter.apply(loan);
            if (value == null) {
                return;
            }
            double v = value.doubleValue();
            Bound b = bounds.get(feature);
            if (Double.isNaN(v) || v < b.hardMin() || v > b.hardMax()) {
                errors.put(feature, "must be between " + format(b.hardMin()) + " and " + format(b.hardMax()));
            } else if (v < b.softMin() || v > b.softMax()) {
                warnings.add(feature + " = " + format(v) + " is outside the range the model was trained on ("
                        + format(b.softMin()) + " to " + format(b.softMax()) + "); treat the result as an extrapolation");
            }
        });
        CATEGORICAL.forEach((feature, getter) -> {
            String value = getter.apply(loan);
            if (value != null && !vocabulary.get(feature).contains(value)) {
                errors.put(feature, "unknown code '" + value + "'; expected one of " + vocabulary.get(feature));
            }
        });
        if (loan.originationMonth() != null) {
            YearMonth origination = YearMonth.parse(loan.originationMonth());
            if (origination.isAfter(YearMonth.now(ZoneOffset.UTC))) {
                errors.put("originationMonth", "cannot be in the future");
            } else if (origination.isBefore(historyStart)) {
                // the rate spread and the property's value at origination need the market data of that month
                errors.put("originationMonth", "cannot be before " + historyStart + ", where the market history starts");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidLoanException(errors);
        }
        return new Result(warnings);
    }

    private static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
