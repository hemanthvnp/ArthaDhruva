package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.springframework.stereotype.Service;

/**
 * The fitted loss given default under the collateral-based LGD ({@link TermStructureEngine#collateralLgd}): one number for
 * every loan, the mean realized loss over the 2,746 defaulted loans with a recorded loss (vintages 2020 to 2024).
 *
 * <p>It replaced a Beta regression on five origination features. Judged out of time (fit on 2020-2021, tested on 2022-2024) that
 * model was no better than this constant: it over-predicted 1.9 times and its rank correlation with realized loss was 0.10.
 * Its card, results and scripts are kept in backend/experiments/retired/lgd, and MODELS.md has the table. In a period of rising
 * house prices about 88% of these defaulted loans lost nothing, so the floor is small and a falling-price scenario is driven by
 * the collateral shortfall, as before.
 */
@Service
public class LgdPredictor {

    /** Mean realized loss over the balance removed; backend/golden_term_structure.py holds the same number. */
    public static final double HISTORICAL_MEAN_LGD = 0.0076;

    public double predict(LoanFeatures loan) {
        return HISTORICAL_MEAN_LGD;
    }
}
