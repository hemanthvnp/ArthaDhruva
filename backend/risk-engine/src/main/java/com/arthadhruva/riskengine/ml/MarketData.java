package com.arthadhruva.riskengine.ml;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The market and macro history the models were trained against, shipped with them:
 * <ul>
 *   <li>the Freddie Mac PMMS 30-year rate by month ({@code market_rates.json}), for the rate spread at
 *       origination and the rate incentive to refinance;</li>
 *   <li>each state's FHFA house price index and unemployment rate by month ({@code macro_state.json}),
 *       for mark-to-market LTV and unemployment dynamics.</li>
 * </ul>
 * Lookups outside the covered range clamp to the nearest covered month: before the series starts, its
 * first value; after it ends, its latest value (the "flat" baseline assumption scenarios build on).
 */
@Component
public class MarketData {

    private final YearMonth rateFirst;
    private final double[] mortgageRate;    // one value per month from rateFirst, gaps carried forward
    private final Map<String, StateSeries> states = new HashMap<>();
    private final YearMonth rateAsOf;
    private final YearMonth macroFirst;
    private final YearMonth macroAsOf;

    private record StateSeries(double[] hpi, double[] unemployment) {
    }

    public MarketData() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rates = mapper.readTree(read("market_rates.json"));
        TreeMap<YearMonth, Double> byMonth = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : rates.get("monthly").properties()) {
            byMonth.put(YearMonth.parse(e.getKey()), e.getValue().asDouble());
        }
        this.rateFirst = byMonth.firstKey();
        this.rateAsOf = YearMonth.parse(rates.get("as_of_month").asString());
        this.mortgageRate = new double[(int) rateFirst.until(byMonth.lastKey(), ChronoUnit.MONTHS) + 1];
        for (int i = 0; i < mortgageRate.length; i++) {
            mortgageRate[i] = byMonth.floorEntry(rateFirst.plusMonths(i)).getValue();
        }

        JsonNode macro = mapper.readTree(read("macro_state.json"));
        this.macroFirst = YearMonth.parse(macro.get("first_month").asString());
        this.macroAsOf = YearMonth.parse(macro.get("last_month").asString());
        for (Map.Entry<String, JsonNode> e : macro.get("states").properties()) {
            JsonNode hpi = e.getValue().get("hpi");
            JsonNode ur = e.getValue().get("unemployment");
            double[] h = new double[hpi.size()];
            double[] u = new double[ur.size()];
            for (int i = 0; i < h.length; i++) {
                h[i] = hpi.get(i).asDouble();
                u[i] = ur.get(i).asDouble();
            }
            states.put(e.getKey(), new StateSeries(h, u));
        }
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream is = MarketData.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    /** Latest month with an observed mortgage rate. */
    public YearMonth rateAsOf() {
        return rateAsOf;
    }

    /** Latest month of state macro data. */
    public YearMonth macroAsOf() {
        return macroAsOf;
    }

    /** First month of the mortgage-rate and state macro history; earlier months read as this one. */
    public YearMonth historyStart() {
        return rateFirst.isAfter(macroFirst) ? rateFirst : macroFirst;
    }

    public double mortgageRate(YearMonth month) {
        long offset = rateFirst.until(month, ChronoUnit.MONTHS);
        return mortgageRate[(int) Math.max(0, Math.min(mortgageRate.length - 1, offset))];
    }

    public double latestMortgageRate() {
        return mortgageRate[mortgageRate.length - 1];
    }

    public boolean hasState(String state) {
        return states.containsKey(state);
    }

    public double hpi(String state, YearMonth month) {
        return series(state).hpi()[index(month, series(state).hpi().length)];
    }

    public double unemployment(String state, YearMonth month) {
        return series(state).unemployment()[index(month, series(state).unemployment().length)];
    }

    private StateSeries series(String state) {
        StateSeries s = states.get(state);
        if (s == null) {
            throw new IllegalArgumentException("No macro data for state " + state);
        }
        return s;
    }

    private int index(YearMonth month, int length) {
        long offset = macroFirst.until(month, ChronoUnit.MONTHS);
        return (int) Math.max(0, Math.min(length - 1, offset));
    }
}
