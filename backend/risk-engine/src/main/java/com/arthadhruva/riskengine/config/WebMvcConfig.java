package com.arthadhruva.riskengine.config;

import com.arthadhruva.riskengine.tenant.TenantHibernateFilterInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantHibernateFilterInterceptor tenantHibernateFilterInterceptor;

    public WebMvcConfig(TenantHibernateFilterInterceptor tenantHibernateFilterInterceptor) {
        this.tenantHibernateFilterInterceptor = tenantHibernateFilterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantHibernateFilterInterceptor);
    }

    /** Every {@code @RestController} is reachable under {@code /v1} so a future breaking change
     * can ship as {@code /v2} without touching existing endpoints (design decision doc, API
     * versioning). Deliberately scoped to {@code @RestController} only -- Spring Boot's own
     * {@code BasicErrorController} (behind {@code /error}, permitted unauthenticated in {@link
     * com.arthadhruva.riskengine.security.SecurityConfig}) is a plain {@code @Controller} and
     * actuator endpoints use a separate handler mapping entirely, so neither gets this prefix. */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/v1", HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
