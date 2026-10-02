package com.arthadhruva.riskengine.regime;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N-step-ahead regime forecasting on top of the HMM fitted in hmm_regime_detector.ipynb (exported by
 * backend/export_hmm.py). The fitting stays in Python; this needs only the fitted transition matrix and
 * the most recent decoded state. Forecasting from there is a Markov chain forecast: apply the transition
 * matrix once per month to the current state's one-hot distribution.
 *
 * <p>The forecast is conditional on the last decoded month, which is as old as the data the model was
 * exported with. The response says how old ({@code dataAgeMonths}) instead of presenting a forecast
 * from stale data as if it started today.
 */
@Service
public class RegimeForecastService {

    /** Ten years: far past the point where the forecast has converged to the stationary mix. */
    public static final int MAX_MONTHS_AHEAD = 120;

    private final double[][] transitionMatrix;
    private final List<String> stateLabels;
    private final int currentStateIndex;
    private final String asOfMonth;
    private final Clock clock;

    public RegimeForecastService() throws IOException {
        this(Clock.systemUTC());
    }

    RegimeForecastService(Clock clock) throws IOException {
        this.clock = clock;
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("hmm_regime.json")) {
            if (is == null) {
                throw new IOException("hmm_regime.json not found on classpath");
            }
            HmmExport export = mapper.readValue(is.readAllBytes(), HmmExport.class);
            this.transitionMatrix = export.transitionMatrix();
            this.stateLabels = List.copyOf(export.stateLabels());
            this.currentStateIndex = export.currentStateIndex();
            this.asOfMonth = export.asOfMonth();
        }
        // Stored in single precision: without this the rows sum to 1 only to about 1e-8, and a long
        // forecast would slowly gain or lose probability.
        for (double[] row : transitionMatrix) {
            double sum = 0;
            for (double p : row) {
                sum += p;
            }
            for (int j = 0; j < row.length; j++) {
                row[j] /= sum;
            }
        }
    }

    /**
     * The regime-probability distribution for each of the next {@code monthsAhead} months after the most
     * recently decoded month.
     */
    public RegimeForecast forecast(int monthsAhead) {
        if (monthsAhead < 0 || monthsAhead > MAX_MONTHS_AHEAD) {
            throw new IllegalArgumentException("monthsAhead must be between 0 and " + MAX_MONTHS_AHEAD);
        }
        LocalDate asOf = LocalDate.parse(asOfMonth);
        double[] distribution = new double[stateLabels.size()];
        distribution[currentStateIndex] = 1.0;
        List<MonthForecast> path = new ArrayList<>(monthsAhead);
        for (int step = 1; step <= monthsAhead; step++) {
            distribution = applyOneStep(distribution);
            path.add(new MonthForecast(asOf.plusMonths(step).toString(), labelled(distribution)));
        }

        Map<String, Double> expectedDuration = new LinkedHashMap<>();
        for (int i = 0; i < stateLabels.size(); i++) {
            double leave = 1 - transitionMatrix[i][i];
            expectedDuration.put(stateLabels.get(i), leave > 0 ? 1 / leave : null);  // null: an absorbing state never ends
        }
        long age = ChronoUnit.MONTHS.between(YearMonth.from(asOf), YearMonth.now(clock.withZone(ZoneOffset.UTC)));
        return new RegimeForecast(asOfMonth, asOf.plusMonths(monthsAhead).toString(), monthsAhead, labelled(distribution),
                stateLabels.get(currentStateIndex), path, labelled(stationary()), expectedDuration, (int) Math.max(0, age));
    }

    private Map<String, Double> labelled(double[] distribution) {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < stateLabels.size(); i++) {
            probabilities.put(stateLabels.get(i), distribution[i]);
        }
        return probabilities;
    }

    /** The long-run mix: the distribution the chain settles into from any start (power iteration). */
    private double[] stationary() {
        double[] distribution = new double[stateLabels.size()];
        distribution[currentStateIndex] = 1.0;
        for (int step = 0; step < 10_000; step++) {
            double[] next = applyOneStep(distribution);
            double change = 0;
            for (int i = 0; i < next.length; i++) {
                change += Math.abs(next[i] - distribution[i]);
            }
            distribution = next;
            if (change < 1e-14) {
                break;
            }
        }
        return distribution;
    }

    private double[] applyOneStep(double[] distribution) {
        double[] next = new double[distribution.length];
        for (int to = 0; to < distribution.length; to++) {
            double sum = 0.0;
            for (int from = 0; from < distribution.length; from++) {
                sum += distribution[from] * transitionMatrix[from][to];
            }
            next[to] = sum;
        }
        return next;
    }

    /**
     * @param regimeProbabilities    the distribution in {@code forecastMonth}
     * @param path                   the distribution in every month up to it
     * @param stationary             the long-run share of months in each regime
     * @param expectedDurationMonths how long a regime lasts on average once entered
     * @param dataAgeMonths          months between the last decoded month and today
     */
    public record RegimeForecast(
            String asOfMonth,
            String forecastMonth,
            int monthsAhead,
            Map<String, Double> regimeProbabilities,
            String currentRegime,
            List<MonthForecast> path,
            Map<String, Double> stationary,
            Map<String, Double> expectedDurationMonths,
            int dataAgeMonths
    ) {
    }

    public record MonthForecast(String month, Map<String, Double> regimeProbabilities) {
    }

    private record HmmExport(
            @JsonProperty("state_labels") List<String> stateLabels,
            @JsonProperty("transition_matrix") double[][] transitionMatrix,
            @JsonProperty("current_state_index") int currentStateIndex,
            @JsonProperty("as_of_month") String asOfMonth
    ) {
    }
}
