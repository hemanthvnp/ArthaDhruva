package com.arthadhruva.riskengine.ratelimit;

/**
 * Strategy: how many requests a caller may make. The filter asks this, not a hard-coded number, so
 * a subscription plan (billing phase) can supply per-tenant limits by providing another
 * implementation without touching the limiter or the filter.
 */
public interface RateLimitPolicy {

    /** Token bucket: holds up to {@code capacity} tokens, refilled at {@code perSecond}. */
    record Limit(int capacity, double perSecond) {
    }

    /** Limit for an authenticated tenant's requests. */
    Limit forTenant(Long tenantId);

    /** Limit for a caller with no tenant yet (login, activation), keyed by client address. */
    Limit forAnonymous();
}
