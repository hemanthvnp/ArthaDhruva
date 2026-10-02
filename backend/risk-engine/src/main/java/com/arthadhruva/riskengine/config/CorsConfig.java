package com.arthadhruva.riskengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Cross-origin access is an explicit allow-list from {@code app.cors-allowed-origins}
 * (comma-separated). The default is the Vite dev server only; in the containerised deployment the
 * frontend and API share one origin behind nginx, so set it empty there and no cross-origin
 * request is permitted at all. Never a wildcard: the API authenticates with bearer tokens a
 * wildcard-permitted page could otherwise be handed by a script running on any site.
 *
 * <p>Exposed as a {@link CorsConfigurationSource} bean (rather than a {@code WebMvcConfigurer}
 * mapping) so Spring Security's CORS filter picks it up and answers preflight before auth runs.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors-allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-API-Key"));
        // Headers a cross-origin page may read: download names, where to poll a started job, how long to
        // back off, list totals and the rate-limit allowance left.
        configuration.setExposedHeaders(List.of("Content-Disposition", "Location", "Retry-After", "X-Total-Count",
                "X-RateLimit-Remaining"));
        // Let the browser cache the preflight answer instead of asking before every request.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
