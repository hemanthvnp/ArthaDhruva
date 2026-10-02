package com.arthadhruva.riskengine.tenant;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean;

/**
 * Enables the Hibernate {@code tenantFilter} (declared on every {@link TenantAware} entity) on every
 * EntityManager the application creates, for the tenant in {@link TenantContext} at that moment.
 *
 * <p>Why an EntityManager initializer. With open-in-view off (required so each transaction tags its own
 * connection with the current tenant), there is no request-scoped session for an MVC interceptor to
 * enable a filter on: the previous interceptor called {@code unwrap(Session.class)} on the shared proxy,
 * which -- verified against Spring's bytecode -- enabled the filter on a throwaway EntityManager that
 * was closed immediately, so the filter never applied to a real query. Every transaction creates its
 * EntityManager through the factory, so the factory's initializer is the one hook every query passes.
 *
 * <p>Scope: Hibernate filters apply to queries (derived, JPQL, criteria) and collections, not to
 * {@code find}/{@code findById} by primary key; tenant-scoped primary keys are composite (tenant, id) where
 * that matters, and Postgres row-level security covers every access path regardless.
 */
@Configuration
public class TenantFilterConfig {

    public static final String FILTER = "tenantFilter";

    @Bean
    static BeanPostProcessor tenantFilterEntityManagerInitializer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractEntityManagerFactoryBean factory) {
                    factory.setEntityManagerInitializer(TenantFilterConfig::enableTenantFilter);
                }
                return bean;
            }
        };
    }

    static void enableTenantFilter(EntityManager entityManager) {
        TenantContext.getOptional().ifPresent(tenantId ->
                entityManager.unwrap(Session.class).enableFilter(FILTER).setParameter("tenantId", tenantId));
    }
}
