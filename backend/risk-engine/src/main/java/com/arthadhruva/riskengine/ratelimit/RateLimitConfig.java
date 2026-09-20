package com.arthadhruva.riskengine.ratelimit;

import com.arthadhruva.riskengine.idempotency.IdempotencyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RateLimitConfig {

    /** Spring Boot registers every Filter bean with the servlet container on its own, in front of
     * the security chain, where no tenant is known yet. Disable that: these filters belong inside
     * the chain (SecurityConfig), after JWT authentication has established the tenant. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter> apiKeyFilterRegistration(
            com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
