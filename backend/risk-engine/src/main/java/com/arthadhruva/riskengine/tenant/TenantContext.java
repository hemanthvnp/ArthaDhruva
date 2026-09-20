package com.arthadhruva.riskengine.tenant;

import java.util.Optional;

/**
 * The current request's tenant, as a ThreadLocal -- safe here because this app is classic
 * servlet-per-thread Spring MVC (no WebFlux, no {@code @Async} offloading anywhere in the
 * codebase). Populated by JwtAuthenticationFilter (from the JWT's {@code org} claim) or directly
 * by AuthController/ActivationController for the handful of endpoints that run before a JWT
 * exists; always cleared in a {@code finally} by whichever of those set it, so a thread returned
 * to a pool (or reused for the next request) never leaks a stale tenant into unrelated work.
 *
 * <p>{@link #get()} fails closed (throws) rather than silently returning null/zero -- a
 * tenant-scoped repository call made with no tenant in context is a bug, and it should fail
 * loudly at the call site rather than risk an unscoped query. {@link #getOptional()} exists only
 * for the genuinely tenant-less paths (e.g. a login attempt against an unresolvable org slug,
 * which is still worth recording).
 *
 * <p>If this app ever adopts virtual-thread-per-request scheduling that hands a request off
 * mid-flight, {@code @Async} offloading, or reactive (WebFlux) controllers, this ThreadLocal
 * approach must be revisited (e.g. propagated via a {@code TaskDecorator}, or migrated to a
 * proper request-scoped context) -- not a concern with the current architecture, but worth
 * knowing why this would silently stop working if that changes.
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
