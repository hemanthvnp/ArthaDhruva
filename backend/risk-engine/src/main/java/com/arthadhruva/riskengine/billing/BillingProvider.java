package com.arthadhruva.riskengine.billing;

import java.util.Optional;

/**
 * Port to a payment provider (Adapter/Strategy). The app depends on this interface, never on
 * Stripe directly, so the provider can be swapped and, with none configured, everything else
 * (signup, plans, seat limits, metering) still works.
 */
public interface BillingProvider {

    /** Creates the provider-side customer for an organization; empty if the provider is unavailable. */
    Optional<String> createCustomer(String organizationName, String email, String organizationSlug);

    /** Moves the customer to the provider-side price for {@code planCode}. Best effort: false on failure. */
    boolean changePlan(String customerId, String planCode);
}
