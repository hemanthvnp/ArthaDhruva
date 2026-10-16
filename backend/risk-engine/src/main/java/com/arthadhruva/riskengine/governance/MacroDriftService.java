package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Are the macro conditions a lifetime projection starts from still like the ones the survival model learned
 * from? The model's macro drivers are each state's unemployment rate, its change over twelve months and the
 * twelve-month change in its house price index, plus the national mortgage rate behind every loan's rate
 * incentive. Three checks, each answerable from what ships with the service:
 *
 * <ul>
 *   <li><b>Distribution.</b> The latest month's cross-section of the 51 states against every state-month of
 *       the model's training window, as a population stability index over the window's quintiles. The
 *       reference is state-months, not the training sample's loan-months (the export writes no loan-weighted
 *       macro reference yet), so it describes the conditions the window contained, not how many loans saw
 *       each. With 51 states in 5 bins, PSI carries about 0.08 of pure sampling noise; it is reported and
 *       judged net of that, as for the PD model's inputs.</li>
 *   <li><b>Envelope.</b> Which states sit outside the range of each driver in the training sample (the
 *       card's {@code training.envelope}, which is loan-months), and how many of this organization's loans
 *       are in them. A projection from such a state extrapolates from its first month.</li>
 *   <li><b>Market rate.</b> The latest mortgage rate against the range and distribution of the training
 *       window's months.</li>
 * </ul>
 */
@Service
public class MacroDriftService {

    static final int BINS = 5;
    static final List<String> DRIVERS = List.of("unemployment", "unemployment_change_12m", "hpi_change_12m");

    /**
     * @param statesOutside states whose latest value lies outside the training envelope
     * @param loansOutside  this organization's loans in those states
     */
    public record Driver(String feature, double envelopeMin, double envelopeMax, double currentMin, double currentMedian,
                         double currentMax, List<String> statesOutside, int loansOutside, DriftService.FeatureDrift drift) {
    }

    /** @param percentile share of the training window's months with a rate at or below the current one */
    public record MarketRate(double current, String asOf, double trainingMin, double trainingMax, double percentile,
                             boolean outsideTraining) {
    }

    /**
     * @param trainingWindow first and last month of the training sample
     * @param loansCovered   this organization's loans in a state with macro data (the others cannot be projected)
     */
    public record MacroDriftReport(String modelVersion, String macroAsOf, List<String> trainingWindow, int states,
                                   int loans, int loansCovered, List<Driver> drivers, MarketRate marketRate,
                                   String reference, Instant computedAt) {
    }

    static final String REFERENCE = "Every state-month of the training window (not loan-weighted); the envelope is "
            + "the training sample's own range.";

    private record Reference(double[] edges, double[] expected) {
    }

    private final SurvivalModel model;
    private final MarketData market;
    private final YearMonth trainFrom;
    private final YearMonth trainTo;
    private final Map<String, Reference> references = new LinkedHashMap<>();
    private final double[] trainingRates;

    public MacroDriftService(SurvivalModel model, MarketData market) {
        this.model = model;
        this.market = market;
        JsonNode months = model.metadata().get("training").get("months");
        this.trainFrom = YearMonth.parse(months.get(0).asString());
        this.trainTo = YearMonth.parse(months.get(1).asString());
        YearMonth firstWithHistory = market.historyStart().plusMonths(12);
        YearMonth from = trainFrom.isBefore(firstWithHistory) ? firstWithHistory : trainFrom;
        for (String driver : DRIVERS) {
            List<Double> values = new ArrayList<>();
            for (String state : market.states()) {
                for (YearMonth m = from; !m.isAfter(trainTo); m = m.plusMonths(1)) {
                    values.add(value(driver, state, m));
                }
            }
            references.put(driver, reference(values.stream().mapToDouble(Double::doubleValue).toArray()));
        }
        int n = (int) trainFrom.until(trainTo, ChronoUnit.MONTHS) + 1;
        this.trainingRates = new double[n];
        for (int i = 0; i < n; i++) {
            trainingRates[i] = market.mortgageRate(trainFrom.plusMonths(i));
        }
        Arrays.sort(trainingRates);
    }

    /** @param loanStates the property state of each loan in the organization's portfolio */
    public MacroDriftReport report(List<String> loanStates) {
        return report(market.macroAsOf(), loanStates);
    }

    /** The report as it would read with {@code asOf} as the latest month (for tests and back-checks). */
    MacroDriftReport report(YearMonth asOf, List<String> loanStates) {
        List<String> states = List.copyOf(market.states());
        Map<String, Integer> loansByState = new LinkedHashMap<>();
        int covered = 0;
        for (String state : loanStates) {
            if (state != null && market.hasState(state)) {
                loansByState.merge(state, 1, Integer::sum);
                covered++;
            }
        }
        List<Driver> drivers = new ArrayList<>();
        for (String driver : DRIVERS) {
            SurvivalModel.Range envelope = model.envelope(driver);
            double[] current = new double[states.size()];
            List<String> outside = new ArrayList<>();
            int loansOutside = 0;
            for (int i = 0; i < states.size(); i++) {
                current[i] = value(driver, states.get(i), asOf);
                if (current[i] < envelope.min() || current[i] > envelope.max()) {
                    outside.add(states.get(i));
                    loansOutside += loansByState.getOrDefault(states.get(i), 0);
                }
            }
            double[] sorted = current.clone();
            Arrays.sort(sorted);
            drivers.add(new Driver(driver, envelope.min(), envelope.max(), sorted[0], quantile(sorted, 0.5),
                    sorted[sorted.length - 1], outside, loansOutside, drift(driver, references.get(driver), current)));
        }
        double rate = market.mortgageRate(asOf.isAfter(market.rateAsOf()) ? market.rateAsOf() : asOf);
        int atOrBelow = 0;
        while (atOrBelow < trainingRates.length && trainingRates[atOrBelow] <= rate) {
            atOrBelow++;
        }
        MarketRate marketRate = new MarketRate(rate, (asOf.isAfter(market.rateAsOf()) ? market.rateAsOf() : asOf).toString(),
                trainingRates[0], trainingRates[trainingRates.length - 1], (double) atOrBelow / trainingRates.length,
                rate < trainingRates[0] || rate > trainingRates[trainingRates.length - 1]);
        return new MacroDriftReport(model.version(), asOf.toString(), List.of(trainFrom.toString(), trainTo.toString()),
                states.size(), loanStates.size(), covered, drivers, marketRate, REFERENCE, Instant.now());
    }

    /** The driver as the term-structure engine computes it at the start of a projection. */
    private double value(String driver, String state, YearMonth m) {
        return switch (driver) {
            case "unemployment" -> market.unemployment(state, m);
            case "unemployment_change_12m" -> market.unemployment(state, m) - market.unemployment(state, m.minusMonths(12));
            case "hpi_change_12m" -> market.hpi(state, m) / market.hpi(state, m.minusMonths(12)) - 1.0;
            default -> throw new IllegalArgumentException("Unknown macro driver " + driver);
        };
    }

    /** Quintile edges of the reference, and the share of it in each bin ([edge, next edge), as DriftService). */
    private static Reference reference(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double[] edges = new double[BINS - 1];
        for (int b = 1; b < BINS; b++) {
            edges[b - 1] = quantile(sorted, (double) b / BINS);
        }
        int[] counts = count(edges, values);
        double[] expected = new double[BINS];
        for (int b = 0; b < BINS; b++) {
            expected[b] = (double) counts[b] / values.length;
        }
        return new Reference(edges, expected);
    }

    private static DriftService.FeatureDrift drift(String driver, Reference reference, double[] current) {
        int[] counts = count(reference.edges(), current);
        double[] edges = reference.edges();
        List<DriftService.Bin> bins = new ArrayList<>(BINS);
        for (int b = 0; b < BINS; b++) {
            String label = b == 0 ? "< " + DriftService.number(edges[0])
                    : b == edges.length ? ">= " + DriftService.number(edges[b - 1])
                    : DriftService.number(edges[b - 1]) + " to " + DriftService.number(edges[b]);
            bins.add(new DriftService.Bin(label, reference.expected()[b], (double) counts[b] / current.length));
        }
        return DriftService.drift(driver, bins, current.length);
    }

    private static int[] count(double[] edges, double[] values) {
        int[] counts = new int[edges.length + 1];
        for (double v : values) {
            int bin = 0;
            while (bin < edges.length && v >= edges[bin]) {
                bin++;
            }
            counts[bin]++;
        }
        return counts;
    }

    /** Linear interpolation between order statistics of a sorted array. */
    static double quantile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(sorted.length - 1, lower + 1);
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }
}
