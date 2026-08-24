package com.arthadhruva.riskengine.trajectory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One month of a loan's actual observed performance -- {@code currentLoanDelinquencyStatus}
 * matches the raw dataset's values ("0", "1", ..., or non-numeric codes like "RA"/"XX"),
 * {@code modificationFlag} matches Freddie Mac's raw "Y"/blank convention (only exactly "Y"
 * counts as modified, mirroring the export script's {@code == "Y"} check).
 */
public record MonthlyRecord(
        @NotBlank String currentLoanDelinquencyStatus,
        @NotNull @PositiveOrZero Double currentActualUpb,
        String modificationFlag
) {
}
