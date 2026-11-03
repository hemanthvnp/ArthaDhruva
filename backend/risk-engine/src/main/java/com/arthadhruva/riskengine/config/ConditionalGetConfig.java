package com.arthadhruva.riskengine.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Conditional GET on the reads that clients poll or fetch repeatedly: a portfolio run's status (polled
 * until it finishes), the model inventory and cards, and the webhook delivery log (polled by the
 * integrations page). The response carries a weak {@code ETag} of its body; a client that sends it back in
 * {@code If-None-Match} gets {@code 304} with no body when nothing changed, so a poll that finds no
 * progress costs a status line instead of the whole document.
 *
 * <p>Registered behind the security chain, so a request is authenticated and scoped to its tenant before
 * anything is compared, and never on streamed exports or file downloads, which this filter would buffer.
 * The responses keep Spring Security's {@code Cache-Control: no-store}: a browser does not keep tenant
 * data on disk, and a client that wants the saving keeps the ETag itself.
 */
@Configuration
class ConditionalGetConfig {

    static final String[] PATTERNS = {"/v1/risk/portfolio/runs/*", "/v1/models", "/v1/models/*", "/v1/models/survival/backtest",
            "/v1/admin/webhooks/deliveries"};

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> conditionalGetFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(PATTERNS);
        registration.setName("conditionalGet");
        return registration;
    }
}
