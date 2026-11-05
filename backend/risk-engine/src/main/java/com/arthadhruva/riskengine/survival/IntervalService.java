package com.arthadhruva.riskengine.survival;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Ranges around a lifetime PD and expected loss: the spread a loan's figures take when the regime chain is not
 * known exactly, widened by how far the model's predictions have been from realized incidence in past vintages.
 *
 * <p><b>Regime uncertainty.</b> The two-regime chain is estimated from a few dozen months containing one stress
 * episode, so the expected stress spell and the long-run stressed share are uncertain, and a projection is only as good
 * as the chain behind it. Each draw replaces the chain's two rows with a Dirichlet sample whose pseudo-counts are
 * {@code n_i x P[i][j]} ({@code n_i} months spent in regime i, from the model card), so the draws centre on the chain of
 * record and spread as widely as that many months can support. Each draw is then projected <i>exactly</i> by the same
 * recursion the point estimate uses, so a draw is an exact expectation, not a simulation. The hazard model's own
 * parameters are not resampled; the response says so.
 *
 * <p><b>Calibration band.</b> The vintage backtest measures realized over predicted cumulative incidence by origination
 * year. The sample standard deviation of its logarithm, {@code sigma}, across the vintages that reach a horizon,
 * widens the draws' 5th to 95th percentile range by {@code exp(-sigma)} below and {@code exp(+sigma)} above. Few
 * vintages stand behind it and they share one macro cycle: the response carries how many.
 *
 * <p><b>Reproducible.</b> Draw {@code d} is a pure function of the request's key and {@code d} (a SplitMix64 stream
 * seeded from the key hash), so the same request gives bit-identical bounds on any thread count and any replica.
 * Draws are computed in index order, then sorted; nothing depends on scheduling.
 *
 * <p>The label is "regime uncertainty plus historical calibration error", never "confidence interval": nothing here has
 * a coverage guarantee, and the card reports how often the band alone held on the backtest vintages.
 */
@Service
public class IntervalService {

    public static final String METHOD = "regime uncertainty plus historical calibration error";
    private static final double LOWER_QUANTILE = 0.05;
    private static final double UPPER_QUANTILE = 0.95;
    private static final int MIN_VINTAGES_FOR_LIFETIME = 4;

    private final SurvivalModel survival;
    private final DelinquencyModel model;
    private final int defaultDraws;
    private final int maxDraws;
    private final long budgetMillis;

    public IntervalService(SurvivalModel survival, DelinquencyModel model,
                           @Value("${risk.interval.default-draws:200}") int defaultDraws,
                           @Value("${risk.interval.max-draws:400}") int maxDraws,
                           @Value("${risk.interval.budget-ms:2000}") long budgetMillis) {
        if (defaultDraws < 1 || maxDraws < defaultDraws) {
            throw new IllegalArgumentException("risk.interval.max-draws must be at least risk.interval.default-draws, which must be at least 1");
        }
        this.survival = survival;
        this.model = model;
        this.defaultDraws = defaultDraws;
        this.maxDraws = maxDraws;
        this.budgetMillis = budgetMillis;
    }

    /** A point estimate with its range; {@code lower <= point <= upper}. */
    public record Interval(double lower, double point, double upper) {
    }

    /**
     * @param method          always {@link #METHOD}
     * @param draws           regime chains drawn (null with a {@code reason}: none were)
     * @param resampled       what was resampled: the regime chain only
     * @param bandSigma       sigma of the calibration band behind the lifetime figures
     * @param bandVintages    how many backtest vintages that sigma rests on
     * @param bandHorizon     the horizon, in months, of the backtest incidence it was measured at
     * @param capApplied      true when fewer draws than asked for were used because of the per-request cap
     * @param reason          why there is no interval, when there is none
     */
    public record Intervals(String method, Integer draws, String resampled, String bandSource, Double bandSigma,
                            Integer bandVintages, Integer bandHorizon, boolean capApplied, String reason, Interval pd12m,
                            Interval pdLifetime, Interval ecl12m, Interval eclLifetime) {
    }

    /** One run of the recursion with a given regime chain, reduced to the figures an interval reports. */
    @FunctionalInterface
    interface Projector {
        MultiStateEngine.Figures run(double[][] chain, double[] initialRegime);
    }

    public int defaultDraws() {
        return defaultDraws;
    }

    public int maxDraws() {
        return maxDraws;
    }

    /** "Point estimate only", with the reason: the response shape when intervals were asked for and cannot be given. */
    public Intervals unavailable(String reason) {
        return new Intervals(METHOD, null, "none", null, null, null, null, false, reason, null, null, null, null);
    }

    /**
     * @param key     a canonical string of everything the figures depend on; the draws are seeded from its hash
     * @param point   the figures at the chain of record
     * @param months  the month the regime distribution is taken at (the projection origin)
     * @param asked   the number of draws requested (null = the default)
     */
    public Intervals compute(String key, YearMonth origin, MultiStateEngine.Figures point, Projector projector, Integer asked) {
        int requested = asked == null ? defaultDraws : asked;
        int draws = Math.min(requested, maxDraws);
        long started = System.nanoTime();
        long seed = fnv1a(key);
        JsonNode uncertainty = model.card().get("uncertainty");
        JsonNode counts = uncertainty.get("regime_counts");
        double[] monthsIn = {counts.get("from_state_months").get(0).asDouble(), counts.get("from_state_months").get(1).asDouble()};
        double[][] chain = survival.transitionMatrix();
        double[][] shape = {{monthsIn[0] * chain[0][0], monthsIn[0] * chain[0][1]}, {monthsIn[1] * chain[1][0], monthsIn[1] * chain[1][1]}};

        double[] pd12 = new double[draws], pd = new double[draws], ecl12 = new double[draws], ecl = new double[draws];
        for (int d = 0; d < draws; d++) {
            Stream stream = new Stream(seed, d);
            double[][] drawn = new double[2][2];
            for (int r = 0; r < 2; r++) {
                double a = stream.gamma(shape[r][0]);
                double b = stream.gamma(shape[r][1]);
                double stayShare = a / (a + b);
                drawn[r][0] = stayShare;
                drawn[r][1] = 1.0 - stayShare;
            }
            MultiStateEngine.Figures f = projector.run(drawn, initialRegime(drawn, origin));
            pd12[d] = f.pd12m();
            pd[d] = f.pdLifetime();
            ecl12[d] = f.ecl12m();
            ecl[d] = f.eclLifetime();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMillis > budgetMillis) {
            return unavailable("the interval time budget of " + budgetMillis + " ms was exceeded (" + elapsedMillis
                    + " ms for " + draws + " draws); the point estimate stands");
        }
        Band shortBand = band(12);
        Band lifetimeBand = lifetimeBand();
        boolean capped = requested > maxDraws;
        return new Intervals(METHOD, draws, "regime chain only (the hazard model's own parameters are not resampled)",
                "survival_backtest.json, " + lifetimeBand.vintages + " origination-year vintages",
                lifetimeBand.sigma, lifetimeBand.vintages, lifetimeBand.horizon, capped, null,
                interval(pd12, point.pd12m(), shortBand.sigma, true), interval(pd, point.pdLifetime(), lifetimeBand.sigma, true),
                interval(ecl12, point.ecl12m(), shortBand.sigma, false), interval(ecl, point.eclLifetime(), lifetimeBand.sigma, false));
    }

    private static Interval interval(double[] draws, double point, double sigma, boolean probability) {
        double[] sorted = draws.clone();
        Arrays.sort(sorted);
        double lower = Math.min(quantile(sorted, LOWER_QUANTILE), point);
        double upper = Math.max(quantile(sorted, UPPER_QUANTILE), point);
        lower *= Math.exp(-sigma);
        upper *= Math.exp(sigma);
        if (probability) {
            upper = Math.min(1.0, upper);
        }
        lower = Math.max(0.0, Math.min(lower, point));
        upper = Math.max(upper, point);
        return new Interval(lower, point, upper);
    }

    /** Nearest-rank quantile of sorted values. */
    static double quantile(double[] sorted, double q) {
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private record Band(double sigma, int vintages, int horizon) {
    }

    private Band band(int horizon) {
        JsonNode node = model.card().get("uncertainty").get("calibration_band").get("horizons").get("default").get(Integer.toString(horizon));
        if (node == null) {
            return new Band(0.0, 0, horizon);
        }
        return new Band(node.get("sigma").asDouble(), node.get("vintages").asInt(), horizon);
    }

    /** The longest horizon with enough vintages: lifetime figures are judged against the longest history there is. */
    private Band lifetimeBand() {
        JsonNode horizons = model.card().get("uncertainty").get("calibration_band").get("horizons").get("default");
        Band best = null;
        for (var e : horizons.properties()) {
            int h = Integer.parseInt(e.getKey());
            int vintages = e.getValue().get("vintages").asInt();
            if (vintages >= MIN_VINTAGES_FOR_LIFETIME && (best == null || h > best.horizon)) {
                best = new Band(e.getValue().get("sigma").asDouble(), vintages, h);
            }
        }
        return best == null ? new Band(0.0, 0, 0) : best;
    }

    /**
     * The regime distribution at {@code origin} for a drawn chain: the decoded regime of the latest month the series
     * covers, carried through that chain to {@code origin}, as {@link SurvivalModel#regimeDistribution} does for the
     * chain of record.
     */
    double[] initialRegime(double[][] chain, YearMonth origin) {
        YearMonth anchor = origin.isAfter(survival.regimeAsOf()) ? survival.regimeAsOf() : origin;
        double[] d = new double[2];
        d[survival.decodedRegime(anchor)] = 1.0;
        long steps = anchor.until(origin, ChronoUnit.MONTHS);
        for (long s = 0; s < steps; s++) {
            double calm = d[0] * chain[0][0] + d[1] * chain[1][0];
            d[1] = d[0] * chain[0][1] + d[1] * chain[1][1];
            d[0] = calm;
        }
        return d;
    }

    static long fnv1a(String text) {
        long hash = 0xCBF29CE484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    /** SplitMix64 stream of draw {@code index}: a pure function of the seed and the index. */
    static final class Stream {
        private static final long GAMMA = 0x9E3779B97F4A7C15L;
        private long state;

        Stream(long seed, int index) {
            this.state = mix(seed + (index + 1L) * GAMMA);
        }

        private static long mix(long x) {
            long z = x;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        double unit() {
            state += GAMMA;
            return ((mix(state) >>> 11) + 0.5) * 0x1.0p-53;     // (0, 1): never exactly 0 or 1
        }

        private double normal() {
            return Math.sqrt(-2.0 * Math.log(unit())) * Math.cos(2.0 * Math.PI * unit());
        }

        /** Gamma(shape, 1) by Marsaglia and Tsang, with the boost for shape below 1. */
        double gamma(double shape) {
            if (shape < 1.0) {
                return gamma(shape + 1.0) * Math.pow(unit(), 1.0 / shape);
            }
            double d = shape - 1.0 / 3.0;
            double c = 1.0 / Math.sqrt(9.0 * d);
            while (true) {
                double x = normal();
                double v = 1.0 + c * x;
                if (v <= 0) {
                    continue;
                }
                v = v * v * v;
                double u = unit();
                if (Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) {
                    return d * v;
                }
            }
        }
    }
}
