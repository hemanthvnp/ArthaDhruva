package com.arthadhruva.riskengine.expectedloss;

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
 */
public record ExpectedLossResponse(double pd, double lgd, double ead, double expectedLoss, double pdLifetime,
                                   double expectedLossLifetime, int stage, double eclIfrs9, double expectedLifeMonths,
                                   String modelVersion, List<String> warnings) {
}
