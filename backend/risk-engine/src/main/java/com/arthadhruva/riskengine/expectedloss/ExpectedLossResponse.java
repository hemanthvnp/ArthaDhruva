package com.arthadhruva.riskengine.expectedloss;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * @param pd                   probability of default over the next 12 months (survival model, baseline scenario)
 * @param lgd                  loss given default today, as a fraction of exposure: the fitted LGD, or the
 *                             shortfall a forced sale of the collateral would leave if that is larger
 * @param ead                  exposure today: the scheduled (amortized) balance
 * @param expectedLoss         12-month expected credit loss: each month's default probability times LGD
 *                             times that month's balance, discounted at the note rate. Close to, but not
 *                             exactly, pd x lgd x ead, because the balance falls and losses are discounted
 * @param pdLifetime           probability of default before the loan matures or prepays
 * @param expectedLossLifetime lifetime expected credit loss (the CECL allowance)
 * @param stage                IFRS 9 stage
 * @param eclIfrs9             the IFRS 9 allowance: 12-month ECL in stage 1, lifetime in stage 2
 * @param expectedLifeMonths   months the loan is expected to stay on the book
 * @param warnings             inputs outside the training distribution
 * @param modelUsed            only when the loan's status was given (the status endpoint): the id and version of the model
 *                             that ran, the survival model or the delinquency model
 * @param fallbackReason       only then: why the delinquency model was not used, when it was not
 */
public record ExpectedLossResponse(double pd, double lgd, double ead, double expectedLoss, double pdLifetime,
                                   double expectedLossLifetime, int stage, double eclIfrs9, double expectedLifeMonths,
                                   String modelVersion, List<String> warnings,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String modelUsed,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String fallbackReason) {

    /** The response of a request that gave no status: the two fields about the model used are absent, as they always were. */
    public ExpectedLossResponse(double pd, double lgd, double ead, double expectedLoss, double pdLifetime,
                                double expectedLossLifetime, int stage, double eclIfrs9, double expectedLifeMonths,
                                String modelVersion, List<String> warnings) {
        this(pd, lgd, ead, expectedLoss, pdLifetime, expectedLossLifetime, stage, eclIfrs9, expectedLifeMonths, modelVersion,
                warnings, null, null);
    }
}
