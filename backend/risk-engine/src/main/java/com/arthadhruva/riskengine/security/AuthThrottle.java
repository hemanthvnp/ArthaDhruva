package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.ratelimit.RateLimitPolicy;
import com.arthadhruva.riskengine.ratelimit.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Credential-endpoint throttling, per client address (and, for password-reset mail, per account).
 *
 * <p>This replaced a single global limit on /login: a global cap is a lever for denial of service --
 * a handful of addresses saturating it locked every user out. Per-address buckets stop floods and
 * password spraying from any one source without letting that source affect anyone else. Guessing
 * against a single account is stopped separately and more strictly by the account lockout.
 */
@Component
public class AuthThrottle {

    public enum Kind {
        /** Password logins, SSO hand-off exchange: 10 at once, then 1 every 2 seconds per address. */
        LOGIN(new RateLimitPolicy.Limit(10, 0.5)),
        /** Second-factor submissions outside login (confirm, disable). */
        SECOND_FACTOR(new RateLimitPolicy.Limit(10, 0.5)),
        /** Invite activation and reset completion (token-bearing). */
        TOKEN_REDEMPTION(new RateLimitPolicy.Limit(10, 0.2)),
        /** Reset-mail requests per address. */
        RESET_REQUEST(new RateLimitPolicy.Limit(5, 1.0 / 60));

        private final RateLimitPolicy.Limit limit;

        Kind(RateLimitPolicy.Limit limit) {
            this.limit = limit;
        }
    }

    /** At most 3 reset mails per account, refilling one every 20 minutes: nobody can mail-bomb a user. */
    private static final RateLimitPolicy.Limit RESET_MAIL_PER_ACCOUNT = new RateLimitPolicy.Limit(3, 1.0 / 1200);

    private final RedisRateLimiter limiter;

    public AuthThrottle(RedisRateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Empty if allowed; otherwise the 429 response to return. */
    public Optional<ResponseEntity<Map<String, String>>> check(Kind kind, HttpServletRequest request) {
        return toResponse(acquire("rl:auth:" + kind.name().toLowerCase() + ":" + request.getRemoteAddr(), kind.limit));
    }

    /** Whether one more reset mail may be sent for this account (a denied request still answers generically). */
    public boolean allowResetMail(Long organizationId, String username) {
        return acquire("rl:auth:reset-mail:" + organizationId + ":" + username, RESET_MAIL_PER_ACCOUNT) == 0;
    }

    private long acquire(String key, RateLimitPolicy.Limit limit) {
        return limiter.acquire(List.of(new RedisRateLimiter.Bucket(key, limit)), 1).waitMillis();
    }

    private static Optional<ResponseEntity<Map<String, String>>> toResponse(long waitMs) {
        if (waitMs <= 0) {
            return Optional.empty();
        }
        return Optional.of(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (waitMs + 999) / 1000)))
                .body(Map.of("error", "Too many attempts. Please wait and try again.")));
    }
}
