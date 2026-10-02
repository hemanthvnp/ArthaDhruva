package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Loss given default from the Beta regression fitted in lgd_ead_expected_loss.ipynb (exported by
 * backend/export_lgd_model.py): a logit-link mean equation over five origination features,
 * {@code sigmoid(const + sum coefficient_i * feature_i)}, which reproduces statsmodels'
 * {@code BetaModel.predict()} exactly, so nothing of the Beta distribution needs reimplementing.
 *
 * <p>"Default" here is the first 90-days-past-due month, and most such loans later cure or are repaid
 * from a sale, so the fitted LGD is small (around 1% of balance on 2017-2025 data, a period of rising
 * house prices). It says nothing about a housing downturn; {@link TermStructureEngine#collateralLgd}
 * adds that from the collateral.
 */
@Service
public class LgdPredictor {

    private final double constant;
    private final Map<String, Double> coefficients;

    public LgdPredictor() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        LgdModel model;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("lgd_model.json")) {
            if (is == null) {
                throw new IOException("lgd_model.json not found on classpath");
            }
            model = mapper.readValue(is.readAllBytes(), LgdModel.class);
        }
        this.constant = model.constant();
        this.coefficients = model.coefficients();
    }

    public double predict(LoanFeatures loan) {
        double linear = constant
                + coefficients.get("credit_score") * loan.creditScore()
                + coefficients.get("original_dti") * loan.originalDti()
                + coefficients.get("original_upb") * loan.originalUpb()
                + coefficients.get("original_cltv") * loan.originalCltv()
                + coefficients.get("original_interest_rate") * loan.originalInterestRate();
        return 1.0 / (1.0 + Math.exp(-linear));
    }

    private record LgdModel(
            @JsonProperty("feature_names") java.util.List<String> featureNames,
            @JsonProperty("const") double constant,
            @JsonProperty("coefficients") Map<String, Double> coefficients
    ) {
    }
}
