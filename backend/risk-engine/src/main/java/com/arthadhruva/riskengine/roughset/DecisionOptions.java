package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.TermStructureEngine.InvalidProjectionException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Locale;

/**
 * Asks for a three-way decision about a stressed risk metric, in addition to the usual projection.
 *
 * @param metric    the figure the threshold is applied to
 * @param threshold a variant is "high" when its metric is at or above this
 * @param tolerance variable-precision tolerance in [0, 0.5); 0 (default) is the strict rough-set approximation
 * @param granule   how the variants are drawn; defaults apply when omitted
 * @param seed      overrides the seed derived from the request
 */
public record DecisionOptions(
        @NotNull @Size(max = 30) String metric,
        @NotNull Double threshold,
        Double tolerance,
        @Valid Granule granule,
        Long seed
) {

    public static final int DEFAULT_MEMBERS = 32;
    public static final int MAX_MEMBERS = 128;
    public static final int PORTFOLIO_DEFAULT_MEMBERS = 8;
    public static final int PORTFOLIO_MAX_MEMBERS = 32;
    public static final double DEFAULT_SHOCK_BAND = 0.20;
    public static final double MAX_SHOCK_BAND = 0.50;
    public static final int DEFAULT_TIMING_BAND_MONTHS = 3;
    public static final int MAX_TIMING_BAND_MONTHS = 12;
    public static final int MAX_SCENARIO_SET = 5;
    /** Seeds stay within what a JSON number carries exactly in JavaScript (2^53 - 1), so one read from a response can be sent back. */
    public static final long MAX_SEED = (1L << 53) - 1;
    /** The low 52 bits, for seeds derived from the request. */
    public static final long SEED_MASK = (1L << 52) - 1;

    /**
     * @param kind             PERTURBATION (default) or SCENARIO_SET
     * @param members          granule size including the unperturbed target (perturbation only)
     * @param shockBand        shock magnitudes vary by +/- this fraction (perturbation only)
     * @param timingBandMonths shock timing varies by +/- this many months (perturbation only)
     * @param scenarios        built-in scenario names (scenario set only)
     * @param regimeDraws      regime-chain posterior draws; rejected until that capability exists
     */
    public record Granule(
            @Size(max = 20) String kind,
            Integer members,
            Double shockBand,
            Integer timingBandMonths,
            @Size(max = MAX_SCENARIO_SET) List<@Size(max = 30) String> scenarios,
            Boolean regimeDraws
    ) {
    }

    /** The options after defaults and validation; what the engine and the response both use. */
    public record Resolved(RoughSetMetric metric, double threshold, double tolerance, GranuleKind kind, int members,
                           double shockBand, int timingBandMonths, List<String> scenarios, Long seed) {
    }

    /**
     * Applies defaults and validates; every failure is an {@link InvalidProjectionException} naming the
     * field, which the controllers answer with 422.
     *
     * @param defaultMembers the member count when none is given
     * @param maxMembers     the largest member count this caller may ask for
     */
    public Resolved resolve(int defaultMembers, int maxMembers) {
        RoughSetMetric resolvedMetric;
        try {
            resolvedMetric = RoughSetMetric.valueOf(metric.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidProjectionException("decision.metric must be one of " + List.of(RoughSetMetric.values()));
        }
        if (threshold == null || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new InvalidProjectionException("decision.threshold must be between 0 and 1");
        }
        if (seed != null && (seed > MAX_SEED || seed < -MAX_SEED)) {
            throw new InvalidProjectionException("decision.seed must be between -" + MAX_SEED + " and " + MAX_SEED
                    + ", so that it survives a round trip through a JSON number");
        }
        double beta = tolerance == null ? 0.0 : tolerance;
        if (!Double.isFinite(beta) || beta < 0 || beta >= 0.5) {
            throw new InvalidProjectionException("decision.tolerance must be at least 0 and below 0.5");
        }
        Granule g = granule == null ? new Granule(null, null, null, null, null, null) : granule;
        GranuleKind resolvedKind;
        try {
            resolvedKind = g.kind() == null ? GranuleKind.PERTURBATION : GranuleKind.valueOf(g.kind().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidProjectionException("decision.granule.kind must be one of " + List.of(GranuleKind.values()));
        }
        if (Boolean.TRUE.equals(g.regimeDraws())) {
            throw new InvalidProjectionException("decision.granule.regimeDraws is not available: regime-chain posterior draws "
                    + "have not been built yet");
        }
        if (resolvedKind == GranuleKind.SCENARIO_SET) {
            if (g.scenarios() == null || g.scenarios().isEmpty()) {
                throw new InvalidProjectionException("decision.granule.scenarios must list at least one built-in scenario");
            }
            List<String> names = g.scenarios().stream().map(n -> n.trim().toUpperCase(Locale.ROOT)).distinct().toList();
            if (names.size() > MAX_SCENARIO_SET) {
                throw new InvalidProjectionException("decision.granule.scenarios may list at most " + MAX_SCENARIO_SET + " scenarios");
            }
            return new Resolved(resolvedMetric, threshold, beta, resolvedKind, names.size(), 0.0, 0, names, seed);
        }
        int n = g.members() == null ? defaultMembers : g.members();
        if (n < 1 || n > maxMembers) {
            throw new InvalidProjectionException("decision.granule.members must be between 1 and " + maxMembers);
        }
        double band = g.shockBand() == null ? DEFAULT_SHOCK_BAND : g.shockBand();
        if (!Double.isFinite(band) || band < 0 || band > MAX_SHOCK_BAND) {
            throw new InvalidProjectionException("decision.granule.shockBand must be between 0 and " + MAX_SHOCK_BAND);
        }
        int timing = g.timingBandMonths() == null ? DEFAULT_TIMING_BAND_MONTHS : g.timingBandMonths();
        if (timing < 0 || timing > MAX_TIMING_BAND_MONTHS) {
            throw new InvalidProjectionException("decision.granule.timingBandMonths must be between 0 and " + MAX_TIMING_BAND_MONTHS);
        }
        return new Resolved(resolvedMetric, threshold, beta, resolvedKind, n, band, timing, List.of(), seed);
    }
}
