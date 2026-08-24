package com.arthadhruva.riskengine.regime;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N-step-ahead regime forecasting on top of the HMM fitted in hmm_regime_detector.ipynb
 * (exported by backend/export_hmm.py). The HMM fitting itself stays in Python; this only needs
 * the fitted transition matrix and the most recent known state -- forecasting N steps ahead from
 * there is a Markov chain forecast (apply the transition matrix N times to the current state's
 * one-hot distribution), simple enough not to need re-fitting or re-implementing the HMM in Java.
 */
@Service
public class RegimeForecastService {

    private final double[][] transitionMatrix;
    private final List<String> stateLabels;
    private final int currentStateIndex;
    private final String asOfMonth;

    public RegimeForecastService() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("hmm_regime.json")) {
            if (is == null) {
                throw new IOException("hmm_regime.json not found on classpath");
            }
            HmmExport export = mapper.readValue(is.readAllBytes(), HmmExport.class);
            this.transitionMatrix = export.transitionMatrix();
            this.stateLabels = export.stateLabels();
            this.currentStateIndex = export.currentStateIndex();
            this.asOfMonth = export.asOfMonth();
        }
    }

    /**
     * Forecasts the regime-probability distribution {@code monthsAhead} months from the most
     * recently observed month, by applying the transition matrix {@code monthsAhead} times to
     * the current state's one-hot distribution.
     */
    public RegimeForecast forecast(int monthsAhead) {
        if (monthsAhead < 0) {
            throw new IllegalArgumentException("monthsAhead must be >= 0");
        }
        double[] distribution = new double[stateLabels.size()];
        distribution[currentStateIndex] = 1.0;

        for (int step = 0; step < monthsAhead; step++) {
            distribution = applyOneStep(distribution);
        }

        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < stateLabels.size(); i++) {
            probabilities.put(stateLabels.get(i), distribution[i]);
        }
        LocalDate targetMonth = LocalDate.parse(asOfMonth).plusMonths(monthsAhead);
        return new RegimeForecast(asOfMonth, targetMonth.toString(), monthsAhead, probabilities);
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

    public record RegimeForecast(
            String asOfMonth,
            String forecastMonth,
            int monthsAhead,
            Map<String, Double> regimeProbabilities
    ) {
    }

    private record HmmExport(
            @JsonProperty("state_labels") List<String> stateLabels,
            @JsonProperty("transition_matrix") double[][] transitionMatrix,
            @JsonProperty("current_state_index") int currentStateIndex,
            @JsonProperty("as_of_month") String asOfMonth
    ) {
    }
}
