package com.arthadhruva.riskengine.cvar;

import com.arthadhruva.riskengine.billing.Metered;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Portfolio loss simulation. Not cached: a request without a seed is a fresh simulation by design, and
 * one with a seed is cheap to reproduce.
 */
@RestController
public class CvarController {

    private final CvarEngine cvarEngine;

    public CvarController(CvarEngine cvarEngine) {
        this.cvarEngine = cvarEngine;
    }

    @Metered("CVAR_RUN")
    @PostMapping("/cvar")
    public CvarResult simulate(@Valid @RequestBody CvarRequest request) {
        return cvarEngine.simulate(request);
    }

    @ExceptionHandler(CvarEngine.TooLargeException.class)
    public ResponseEntity<Map<String, String>> onTooLarge(CvarEngine.TooLargeException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CvarEngine.BusyException.class)
    public ResponseEntity<Map<String, String>> onBusy(CvarEngine.BusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(Map.of("error", e.getMessage()));
    }
}
