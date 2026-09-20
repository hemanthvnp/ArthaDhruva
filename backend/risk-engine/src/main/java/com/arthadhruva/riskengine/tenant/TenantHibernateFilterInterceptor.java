package com.arthadhruva.riskengine.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enables the {@code tenantFilter} Hibernate filter (declared on every {@code TenantAware}
 * entity) for the current request's Hibernate session, when a tenant is in context -- the
 * defense-in-depth backstop behind the explicit {@code tenantId} parameters every tenant-scoped
 * repository method already requires (see {@code security.User}'s class doc for why the explicit
 * parameter, not this filter, is the primary guard).
 *
 * <p>This only works because {@code spring.jpa.open-in-view} stays at its Spring Boot default
 * ({@code true}): {@code OpenEntityManagerInViewFilter} binds an {@link EntityManager} to the
 * request thread as a servlet {@code Filter}, which runs <em>before</em> {@code
 * DispatcherServlet} dispatches to any {@code HandlerInterceptor} -- so by the time {@link
 * #preHandle} runs, there's already a live, request-scoped session to enable the filter on.
 * Disabling open-in-view (e.g. to silence its own startup warning) would silently break this --
 * there would be no bound session left to enable the filter on by the time a repository call
 * happens outside the original transaction.
 *
 * <p>Field-injected, not constructor-injected: {@code @PersistenceContext} (the annotation that
 * gets an EntityManager proxy bound to whatever transaction/request is active when a method
 * actually runs, rather than a fixed instance captured at construction time) is only valid on
 * fields/methods per the JPA spec, not constructor parameters -- the one deliberate exception to
 * this codebase's usual constructor-injection style.
 */
@Component
public class TenantHibernateFilterInterceptor implements HandlerInterceptor {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext.getOptional().ifPresent(tenantId ->
                entityManager.unwrap(Session.class).enableFilter("tenantFilter").setParameter("tenantId", tenantId));
        return true;
    }
}
