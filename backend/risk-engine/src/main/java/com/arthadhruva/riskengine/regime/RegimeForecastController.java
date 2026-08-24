package com.arthadhruva.riskengine.regime;

import com.arthadhruva.riskengine.cache.CacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class RegimeForecastController {

    private static final Duration FORECAST_CACHE_TTL = Duration.ofHours(1);

    private final RegimeForecastService regimeForecastService;
    private final CacheService cacheService;

    public RegimeForecastController(RegimeForecastService regimeForecastService, CacheService cacheService) {
        this.regimeForecastService = regimeForecastService;
        this.cacheService = cacheService;
    }

    /**
     * Forecasts the regime-probability distribution N months ahead, via a Markov chain forecast
     * on the HMM fitted in hmm_regime_detector.ipynb. Example: /regime-forecast?monthsAhead=6
     *
     * Cache-aside: the forecast is deterministic for a given monthsAhead until the HMM is
     * refit, so repeated calls are served from Redis instead of recomputing.
     */
    @GetMapping("/regime-forecast")
    public RegimeForecastService.RegimeForecast forecast(
            @RequestParam(defaultValue = "6") int monthsAhead) {
        String key = "regime-forecast:" + monthsAhead;
        return cacheService.get(key, RegimeForecastService.RegimeForecast.class)
                .orElseGet(() -> {
                    RegimeForecastService.RegimeForecast forecast = regimeForecastService.forecast(monthsAhead);
                    cacheService.put(key, forecast, FORECAST_CACHE_TTL);
                    return forecast;
                });
    }
}
