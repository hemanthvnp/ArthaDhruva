package com.arthadhruva.riskengine.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitFilterTest {

    /** A request is charged for the work it starts, and reading is always cheap. */
    @Test
    void heavyRequestsCostMoreThanLightOnes() {
        assertEquals(1, RateLimitFilter.cost("POST", "/v1/score"));
        assertEquals(1, RateLimitFilter.cost("GET", "/v1/loans"));
        assertEquals(2, RateLimitFilter.cost("POST", "/v1/risk/term-structure"));
        assertEquals(5, RateLimitFilter.cost("POST", "/v1/risk/term-structure/compare"));
        assertEquals(5, RateLimitFilter.cost("POST", "/v1/ingest/loans"));
        assertEquals(10, RateLimitFilter.cost("POST", "/v1/cvar"));
        assertEquals(10, RateLimitFilter.cost("POST", "/v1/assistant/chat"));
        assertEquals(20, RateLimitFilter.cost("POST", "/v1/risk/portfolio/runs"));
        // polling a run that is already paid for costs one token, like any other read
        assertEquals(1, RateLimitFilter.cost("GET", "/v1/risk/portfolio/runs/8a6c0c8e-0000-0000-0000-000000000000"));
        assertEquals(1, RateLimitFilter.cost("GET", "/v1/cvar"));
    }
}
