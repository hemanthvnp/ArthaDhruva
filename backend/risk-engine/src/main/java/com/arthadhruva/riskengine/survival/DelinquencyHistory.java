package com.arthadhruva.riskengine.survival;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * A loan's recent payment history, as an optional part of a projection request. With the current status
 * ({@code daysPastDue}) it selects the delinquency model; without it, or when incomplete, the existing engine runs
 * and the response says why.
 *
 * @param monthsObserved  how many of the last 12 months the caller has the loan's status for (1 to 12)
 * @param episodes30Plus  separate spells of 30 or more days past due that began in that window
 * @param monthsSinceCure months since the loan last returned to current after a spell; null when it has not within the
 *                        window (it is never needed for a loan that has had no spell)
 * @param maxStatus12m    the worst status in the window: {@code CURRENT}, {@code D30} or {@code D60}. A loan that reached
 *                        90 days past due has defaulted and is not projected by this model.
 */
public record DelinquencyHistory(
        @Min(1) @Max(12) Integer monthsObserved,
        @Min(0) @Max(12) Integer episodes30Plus,
        @Min(0) @Max(12) Integer monthsSinceCure,
        @Pattern(regexp = "CURRENT|D30|D60", message = "must be CURRENT, D30 or D60") String maxStatus12m
) {
}
