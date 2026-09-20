package com.arthadhruva.riskengine.ratelimit;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs inside the security chain right after JWT authentication, so an authenticated request is
 * limited per tenant and an unauthenticated one (login, activation) per client address. Health
 * and metrics endpoints are exempt so monitoring can never be throttled out.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisRateLimiter limiter;
    private final RateLimitPolicy policy;

    public RateLimitFilter(RedisRateLimiter limiter, RateLimitPolicy policy) {
        this.limiter = limiter;
        this.policy = policy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = TenantContext.getOptional().orElse(null);
        String key = tenantId != null ? "rl:t:" + tenantId : "rl:ip:" + request.getRemoteAddr();
        RateLimitPolicy.Limit limit = tenantId != null ? policy.forTenant(tenantId) : policy.forAnonymous();

        long waitMs = limiter.acquire(key, limit);
        if (waitMs > 0) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (waitMs + 999) / 1000)));
            response.sendError(429, "Too Many Requests");
            return;
        }
        chain.doFilter(request, response);
    }
}
