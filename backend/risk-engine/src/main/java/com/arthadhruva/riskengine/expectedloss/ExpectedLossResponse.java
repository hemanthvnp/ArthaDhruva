package com.arthadhruva.riskengine.expectedloss;

/**
 * @param pd            calibrated probability of default (from the existing PD model)
 * @param lgd           predicted loss given default, as a fraction of exposure
 * @param ead           exposure at default -- original_upb, a stated simplification (see
 *                       ExpectedLossController for why: no post-origination performance history
 *                       exists for a loan being scored at origination time)
 * @param expectedLoss  pd * lgd * ead
 */
public record ExpectedLossResponse(double pd, double lgd, double ead, double expectedLoss) {
}
