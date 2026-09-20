package com.arthadhruva.riskengine.score;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local, per-loan feature attribution by occlusion: for each feature, put it back at the portfolio
 * baseline (median for numeric, most common value for categorical, taken from the demo catalog) and
 * measure how far the calibrated probability moves. That is the first-order, single-feature version
 * of the Shapley idea, honest and model-agnostic, but NOT full SHAP: it ignores feature
 * interactions. It costs F+1 tiny in-process inferences (~17 for this model), so it is computed per
 * request rather than cached. Because it perturbs this loan's own values, two different loans
 * produce different explanations.
 */
@Service
public class ExplanationService {

    private static final int TOP_N = 5;

    private final ModelService model;
    private final LoanCatalogService catalog;
    private volatile float[] baselineVector;

    public ExplanationService(ModelService model, LoanCatalogService catalog) {
        this.model = model;
        this.catalog = catalog;
    }

    public List<ScoreResponse.Attribution> explain(LoanFeatures loan) {
        float[] x = model.featureVectorFor(loan);
        float[] baseline = baseline();
        double full = model.calibratedProbabilityOf(x);
        List<String> names = model.featureNames();

        List<ScoreResponse.Attribution> all = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            if (x[i] == baseline[i]) {
                continue; // already typical: contributes nothing relative to baseline
            }
            float[] probe = x.clone();
            probe[i] = baseline[i];
            all.add(new ScoreResponse.Attribution(names.get(i), full - model.calibratedProbabilityOf(probe)));
        }
        return all.stream()
                .sorted(Comparator.comparingDouble((ScoreResponse.Attribution a) -> Math.abs(a.contribution())).reversed())
                .limit(TOP_N).toList();
    }

    private float[] baseline() {
        float[] b = baselineVector;
        if (b == null) {
            b = model.featureVectorFor(typicalLoan(catalog.demoLoans()));
            baselineVector = b;
        }
        return b;
    }

    private static LoanFeatures typicalLoan(List<LoanFeatures> loans) {
        return new LoanFeatures(null,
                (int) Math.round(median(loans, l -> l.creditScore().doubleValue())),
                median(loans, LoanFeatures::originalDti), median(loans, LoanFeatures::originalUpb),
                median(loans, LoanFeatures::originalCltv), median(loans, LoanFeatures::originalLtv),
                median(loans, LoanFeatures::originalInterestRate),
                (int) Math.round(median(loans, l -> l.originalLoanTerm().doubleValue())),
                (int) Math.round(median(loans, l -> l.numberOfBorrowers().doubleValue())),
                (int) Math.round(median(loans, l -> l.numberOfUnits().doubleValue())),
                median(loans, LoanFeatures::miPercent),
                mode(loans, LoanFeatures::occupancyStatus), mode(loans, LoanFeatures::propertyType),
                mode(loans, LoanFeatures::loanPurpose), mode(loans, LoanFeatures::channel),
                mode(loans, LoanFeatures::firstTimeHomebuyerFlag), mode(loans, LoanFeatures::propertyState));
    }

    private static double median(List<LoanFeatures> loans, java.util.function.ToDoubleFunction<LoanFeatures> f) {
        double[] v = loans.stream().mapToDouble(f).sorted().toArray();
        return v.length == 0 ? 0 : v[v.length / 2];
    }

    private static String mode(List<LoanFeatures> loans, java.util.function.Function<LoanFeatures, String> f) {
        Map<String, Integer> counts = new HashMap<>();
        loans.forEach(l -> counts.merge(f.apply(l), 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }
}
