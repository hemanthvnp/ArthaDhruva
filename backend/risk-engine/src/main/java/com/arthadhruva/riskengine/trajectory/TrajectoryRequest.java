package com.arthadhruva.riskengine.trajectory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A loan's actual trajectory so far, ordered from month 0 (origination) onward. Capped at 12
 * months -- the model (lstm_trajectory_model.ipynb) was only ever trained on up to 12 months of
 * input; scoring beyond that would extrapolate outside its trained domain.
 */
public record TrajectoryRequest(
        @NotNull @Positive Double originalUpb,
        @NotEmpty @Size(max = 12) List<@Valid MonthlyRecord> months
) {
}
