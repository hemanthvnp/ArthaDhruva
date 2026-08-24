package com.arthadhruva.riskengine.cvar;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stochastic simulation endpoint -- deliberately uncached, unlike {@code /score} and
 * {@code /regime-forecast}, since every call is expected to vary slightly by design.
 */
@RestController
public class CvarController {

    private final CvarEngine cvarEngine;

    public CvarController(CvarEngine cvarEngine) {
        this.cvarEngine = cvarEngine;
    }

    @PostMapping("/cvar")
    public CvarResult simulate(@Valid @RequestBody CvarRequest request) {
        return cvarEngine.simulate(request);
    }
}
