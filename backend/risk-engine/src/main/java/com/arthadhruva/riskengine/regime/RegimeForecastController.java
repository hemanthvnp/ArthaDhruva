package com.arthadhruva.riskengine.regime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegimeForecastController {

    private final RegimeForecastService regimeForecastService;

    public RegimeForecastController(RegimeForecastService regimeForecastService) {
        this.regimeForecastService = regimeForecastService;
    }

    /**
     * Forecasts the regime-probability distribution N months ahead, via a Markov chain forecast
     * on the HMM fitted in hmm_regime_detector.ipynb. Example: /regime-forecast?monthsAhead=6
     */
    @GetMapping("/regime-forecast")
    public RegimeForecastService.RegimeForecast forecast(
            @RequestParam(defaultValue = "6") int monthsAhead) {
        return regimeForecastService.forecast(monthsAhead);
    }
}
