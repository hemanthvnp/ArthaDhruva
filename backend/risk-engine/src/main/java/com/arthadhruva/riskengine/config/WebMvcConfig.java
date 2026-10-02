package com.arthadhruva.riskengine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Every {@code @RestController} is reachable under {@code /v1} so a future breaking change can ship
     * as {@code /v2} without touching existing endpoints. Scoped to {@code @RestController}: Spring Boot's
     * {@code BasicErrorController} ({@code /error}) and the actuator endpoints keep their own paths. */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/v1", HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
