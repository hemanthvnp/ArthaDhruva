package com.arthadhruva.riskengine.regime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegimeForecastServiceTest {

    private static RegimeForecastService at(String instant) throws Exception {
        return new RegimeForecastService(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void theForecastStartsAtTheDecodedRegimeAndRelaxesToTheLongRunMix() throws Exception {
        RegimeForecastService.RegimeForecast forecast = at("2026-10-01T00:00:00Z").forecast(120);
        assertEquals(120, forecast.path().size());
        assertEquals(forecast.regimeProbabilities(), forecast.path().get(119).regimeProbabilities());
        String current = forecast.currentRegime();
        double previous = 1.0;
        for (RegimeForecastService.MonthForecast month : forecast.path()) {
            Map<String, Double> p = month.regimeProbabilities();
            assertEquals(1.0, p.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12, month.month());
            assertTrue(p.get(current) < previous, "confidence in today's regime decays every month");
            previous = p.get(current);
        }
        assertTrue(previous > forecast.stationary().get(current));
        assertEquals(1.0, forecast.stationary().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        forecast.expectedDurationMonths().values().forEach(months -> assertTrue(months > 1));
    }

    @Test
    void zeroMonthsAheadIsToday() throws Exception {
        RegimeForecastService.RegimeForecast now = at("2026-10-01T00:00:00Z").forecast(0);
        assertTrue(now.path().isEmpty());
        assertEquals(1.0, now.regimeProbabilities().get(now.currentRegime()), 0.0);
        assertEquals(now.asOfMonth(), now.forecastMonth());
    }

    /** The forecast is anchored to the last decoded month and says how old that is. */
    @Test
    void reportsTheAgeOfItsData() throws Exception {
        RegimeForecastService.RegimeForecast inOctober = at("2026-10-15T00:00:00Z").forecast(1);
        assertEquals("2026-03-01", inOctober.asOfMonth());
        assertEquals(7, inOctober.dataAgeMonths());
        assertEquals(0, at("2026-03-20T00:00:00Z").forecast(1).dataAgeMonths());
        assertEquals("2026-04-01", inOctober.forecastMonth());
    }

    @Test
    void theHorizonIsBounded() throws Exception {
        RegimeForecastService service = at("2026-10-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> service.forecast(-1));
        assertThrows(IllegalArgumentException.class, () -> service.forecast(RegimeForecastService.MAX_MONTHS_AHEAD + 1));
    }
}
