package com.arthadhruva.riskengine.survival;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Locale;

/**
 * A forward macro path: what the regime, unemployment, house prices and mortgage rates do over the
 * projection. Paths start from the latest observed data (per state for unemployment and house prices).
 *
 * @param stressedMonths            months forced into the stressed regime from the start (0 = none); after
 *                                  them, and throughout when 0, the regime follows the fitted Markov chain
 * @param unemploymentShockPp       change in the unemployment rate reached after {@code unemploymentShockMonths}
 *                                  (linear ramp)
 * @param unemploymentRecoveryMonths months over which the shock then fades back to zero (0 = it never does)
 * @param hpiShockPct               house-price decline reached after {@code hpiShockMonths} (log-linear), e.g. 25 = -25%
 * @param hpiAnnualGrowthPct        house-price growth outside the shock window
 * @param rateShockBp               parallel shift of the market mortgage rate, applied from the first month
 *                                  (drives the incentive to refinance)
 */
public record Scenario(
        @NotBlank @Size(max = 30) String name,
        @Size(max = 300) String description,
        @NotNull @Min(0) @Max(120) Integer stressedMonths,
        @NotNull @DecimalMin("-5") @DecimalMax("15") Double unemploymentShockPp,
        @NotNull @Min(1) @Max(60) Integer unemploymentShockMonths,
        @NotNull @Min(0) @Max(120) Integer unemploymentRecoveryMonths,
        @NotNull @DecimalMin("-30") @DecimalMax("60") Double hpiShockPct,
        @NotNull @Min(1) @Max(60) Integer hpiShockMonths,
        @NotNull @DecimalMin("-10") @DecimalMax("15") Double hpiAnnualGrowthPct,
        @NotNull @DecimalMin("-400") @DecimalMax("600") Double rateShockBp
) {
    /** Regimes follow the Markov chain from today's regime; unemployment and rates flat; house prices +3% a year. */
    public static final Scenario BASELINE = new Scenario("BASELINE",
            "Regimes follow the fitted Markov chain from today's regime; unemployment flat; house prices +3% a year; rates flat.",
            0, 0.0, 12, 0, 0.0, 12, 3.0, 0.0);

    /** A moderate downturn in the style of a supervisory adverse scenario (illustrative, not a regulator's). */
    public static final Scenario ADVERSE = new Scenario("ADVERSE",
            "Stressed regime for 12 months; unemployment +3 pp over 12 months, fading over 36; house prices -10% over 12 months, then +2% a year.",
            12, 3.0, 12, 36, 10.0, 12, 2.0, 0.0);

    /** A severe recession in the style of a supervisory severely adverse scenario (illustrative). */
    public static final Scenario SEVERELY_ADVERSE = new Scenario("SEVERELY_ADVERSE",
            "Stressed regime for 24 months; unemployment +5 pp over 12 months, fading over 48; house prices -25% over 24 months, then +2% a year.",
            24, 5.0, 12, 48, 25.0, 24, 2.0, 0.0);

    /** Extension risk: higher rates remove the incentive to refinance, so loans stay on the book longer. */
    public static final Scenario RATES_UP_200 = new Scenario("RATES_UP_200",
            "Mortgage rates +200 bp; otherwise the baseline. Prepayments slow and loans stay on the book longer.",
            0, 0.0, 12, 0, 0.0, 12, 3.0, 200.0);

    /** A refinancing wave: lower rates pull prepayments forward and shorten exposure. */
    public static final Scenario RATES_DOWN_200 = new Scenario("RATES_DOWN_200",
            "Mortgage rates -200 bp; otherwise the baseline. A refinancing wave shortens exposure.",
            0, 0.0, 12, 0, 0.0, 12, 3.0, -200.0);

    public static final List<Scenario> BUILT_IN = List.of(BASELINE, ADVERSE, SEVERELY_ADVERSE, RATES_UP_200, RATES_DOWN_200);

    public static Scenario named(String name) {
        String wanted = name == null || name.isBlank() ? BASELINE.name() : name.trim().toUpperCase(Locale.ROOT);
        return BUILT_IN.stream().filter(s -> s.name().equals(wanted)).findFirst().orElseThrow(() ->
                new IllegalArgumentException("Unknown scenario '" + name + "'; expected one of "
                        + BUILT_IN.stream().map(Scenario::name).toList()));
    }

    /** Unemployment change from the starting level after {@code k} months: ramp up, then fade. */
    double unemploymentDelta(int k) {
        if (k <= 0) {
            return 0;
        }
        if (k <= unemploymentShockMonths) {
            return unemploymentShockPp * k / unemploymentShockMonths;
        }
        if (unemploymentRecoveryMonths == 0) {
            return unemploymentShockPp;
        }
        return unemploymentShockPp * Math.max(0.0, 1.0 - (double) (k - unemploymentShockMonths) / unemploymentRecoveryMonths);
    }

    /** House-price index relative to the starting level after {@code k} months. */
    double hpiFactor(int k) {
        if (k <= 0) {
            return 1.0;
        }
        double growth = Math.log1p(hpiAnnualGrowthPct / 100.0) / 12.0;
        if (hpiShockPct == 0) {
            return Math.exp(growth * k);
        }
        double trough = Math.log1p(-hpiShockPct / 100.0);
        if (k <= hpiShockMonths) {
            return Math.exp(trough * k / hpiShockMonths);
        }
        return Math.exp(trough + growth * (k - hpiShockMonths));
    }
}
