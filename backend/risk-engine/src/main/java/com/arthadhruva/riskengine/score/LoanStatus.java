package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.survival.DelinquencyHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Where a stored loan stands now, as an optional companion to its origination features: how many days past due it is and
 * its recent payment history. Both are what a servicing system knows and the origination record does not. With both, a
 * portfolio run projects the loan from where it is (the delinquency model); with either missing the run uses the
 * existing engine for that loan and says so (see {@code DelinquencyRouter}).
 *
 * @param daysPastDue current delinquency (0 = current)
 * @param history     the last 12 months of payment history, summarised
 */
public record LoanStatus(
        @Min(0) @Max(3650) Integer daysPastDue,
        @Valid DelinquencyHistory history
) {
}
