package com.arthadhruva.riskengine.regime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class RegimeForecastController {

    private final RegimeForecastService regimeForecastService;

    public RegimeForecastController(RegimeForecastService regimeForecastService) {
        this.regimeForecastService = regimeForecastService;
    }

    /**
     * The regime-probability distribution month by month up to N months ahead, a Markov chain forecast on
     * the fitted HMM. Example: /regime-forecast?monthsAhead=6
     *
     * <p>Not cached: the whole forecast is a few hundred multiplications, far cheaper than the round trip
     * to Redis that used to "save" it.
     */
    @GetMapping("/regime-forecast")
    public RegimeForecastService.RegimeForecast forecast(
            @RequestParam(defaultValue = "6") @Min(0) @Max(RegimeForecastService.MAX_MONTHS_AHEAD) int monthsAhead) {
        return regimeForecastService.forecast(monthsAhead);
    }
}
