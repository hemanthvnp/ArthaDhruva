package com.arthadhruva.riskengine.ratelimit;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Runs inside the security chain right after authentication, so an authenticated request is limited
 * per organization and an unauthenticated one (login, activation) per client address. Health and
 * metrics endpoints are exempt so monitoring can never be throttled out.
 *
 * <p>Two refinements over one bucket per organization:
 * <ul>
 *   <li><b>Cost.</b> A request pays for the work it asks for ({@link #cost}): a Monte Carlo simulation
 *       or a portfolio run takes thousands of times the CPU of reading a score, so counting them the same
 *       would let a handful of heavy calls do what the limit exists to prevent.</li>
 *   <li><b>A bucket per caller</b> inside the organization's, sized as a share of it: one script or one
 *       stuck browser tab cannot spend the allowance of every colleague.</li>
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisRateLimiter limiter;
    private final RateLimitPolicy policy;
    private final double callerShare;

    public RateLimitFilter(RedisRateLimiter limiter, RateLimitPolicy policy,
                           @Value("${ratelimit.caller-share:0.5}") double callerShare) {
        this.limiter = limiter;
        this.policy = policy;
        this.callerShare = callerShare;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RedisRateLimiter.Decision decision = limiter.acquire(buckets(request), cost(request.getMethod(), request.getRequestURI()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (decision.waitMillis() + 999) / 1000)));
            response.sendError(429, "Too Many Requests");
            return;
        }
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        chain.doFilter(request, response);
    }

    private List<RedisRateLimiter.Bucket> buckets(HttpServletRequest request) {
        Long tenantId = TenantContext.getOptional().orElse(null);
        if (tenantId == null) {
            return List.of(new RedisRateLimiter.Bucket("rl:ip:" + request.getRemoteAddr(), policy.forAnonymous()));
        }
        RateLimitPolicy.Limit tenant = policy.forTenant(tenantId);
        String tenantKey = "rl:{t:" + tenantId + "}";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return List.of(new RedisRateLimiter.Bucket(tenantKey, tenant));
        }
        RateLimitPolicy.Limit caller = new RateLimitPolicy.Limit(Math.max(1, (int) Math.round(tenant.capacity() * callerShare)),
                tenant.perSecond() * callerShare);
        return List.of(new RedisRateLimiter.Bucket(tenantKey, tenant),
                new RedisRateLimiter.Bucket(tenantKey + ":u:" + authentication.getName(), caller));
    }

    /** Tokens a request costs: 1 unless it starts heavy computation or bulk ingestion. */
    static int cost(String method, String path) {
        if (!"POST".equals(method)) {
            return 1;
        }
        return switch (path) {
            case "/v1/risk/portfolio/runs" -> 20;
            case "/v1/cvar", "/v1/risk/portfolio/loss-distribution", "/v1/assistant/chat" -> 10;
            case "/v1/risk/term-structure/compare", "/v1/admin/portfolio", "/v1/ingest/loans" -> 5;
            case "/v1/risk/term-structure" -> 2;
            default -> 1;
        };
    }
}
