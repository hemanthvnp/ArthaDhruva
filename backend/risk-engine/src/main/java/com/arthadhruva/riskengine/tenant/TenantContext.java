package com.arthadhruva.riskengine.tenant;

import java.util.Optional;

/**
 * The current request's tenant, as a ThreadLocal -- safe because this app is servlet-per-thread
 * Spring MVC. The one place work leaves the request thread, {@code @Async} event listeners, copies it
 * across with a TaskDecorator (see event.AsyncConfig); scheduled jobs set it per tenant themselves.
 * Populated by JwtAuthenticationFilter (from the JWT's {@code org} claim), ApiKeyAuthenticationFilter,
 * or directly by the few public endpoints that run before a session exists; always cleared in a
 * {@code finally} by whichever set it, so a pooled thread never leaks a stale tenant.
 *
 * <p>{@link #get()} fails closed (throws) rather than silently returning null/zero -- a
 * tenant-scoped repository call made with no tenant in context is a bug, and it should fail
 * loudly at the call site rather than risk an unscoped query. {@link #getOptional()} exists only
 * for the genuinely tenant-less paths (e.g. a login attempt against an unresolvable org slug,
 * which is still worth recording).
 *
 * <p>Any new hand-off to another thread (an executor, a parallel stream, reactive code) must propagate
 * the tenant the way AsyncConfig does, or the work runs with no tenant -- which row-level security
 * turns into "sees nothing", a failure that is safe but silent.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        Long tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant set in TenantContext for this thread");
        }
        return tenantId;
    }

    public static Optional<Long> getOptional() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
