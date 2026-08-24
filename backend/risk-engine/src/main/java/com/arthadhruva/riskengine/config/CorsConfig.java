package com.arthadhruva.riskengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Allows the frontend dev server (Vite, localhost:5173) to call this API cross-origin.
 *
 * Exposed as a {@link CorsConfigurationSource} bean (rather than a {@code WebMvcConfigurer}
 * {@code addCorsMappings} override) specifically so {@code SecurityConfig}'s
 * {@code .cors(Customizer.withDefaults())} can pick it up: Spring Security's filter chain runs
 * before Spring MVC's own CORS handling, so without wiring this bean into Security too, a
 * cross-origin preflight (OPTIONS) request would be rejected by the authentication rules before
 * ever reaching CORS logic -- breaking login and every authenticated request from the browser.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
