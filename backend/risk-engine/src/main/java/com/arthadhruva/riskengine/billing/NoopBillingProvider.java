package com.arthadhruva.riskengine.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Used when no provider is configured: plans and limits are enforced locally, nothing is charged. */
@Component
@ConditionalOnExpression("'${billing.stripe.secret-key:}' == ''")
class NoopBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopBillingProvider.class);

    @Override
    public Optional<String> createCustomer(String organizationName, String email, String organizationSlug) {
        log.info("No billing provider configured; skipping customer creation for '{}'", organizationSlug);
        return Optional.empty();
    }

    @Override
    public boolean changePlan(String customerId, String planCode) {
        return true;
    }
}
