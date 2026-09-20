package com.arthadhruva.riskengine.ratelimit;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Distributed token bucket. The whole read-refill-consume step is one Lua script, which Redis runs
 * atomically, so any number of app instances share one accurate bucket per key (an in-process
 * limiter gives each replica its own bucket, silently multiplying the limit by the replica count).
 * Time comes from Redis itself ({@code TIME}), not the callers, so clock skew between instances
 * cannot mis-refill. State expires on its own once a bucket would have fully refilled twice.
 *
 * <p>Fails open when Redis is unavailable (circuit breaker + fallback), consistent with the cache:
 * a rate limiter outage must not become an API outage.
 */
@Service
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final DefaultRedisScript<Long> BUCKET = new DefaultRedisScript<>("""
            local cap = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(d[1]) or cap
            local ts = tonumber(d[2]) or now
            tokens = math.min(cap, tokens + (now - ts) * rate / 1000)
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
            else
              wait = math.ceil((1 - tokens) / rate * 1000)
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 2000) + 1000)
            return wait
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 0 if the request is allowed, otherwise milliseconds until a token is available. */
    @CircuitBreaker(name = "redis", fallbackMethod = "failOpen")
    public long acquire(String key, RateLimitPolicy.Limit limit) {
        Long wait = redis.execute(BUCKET, List.of(key), String.valueOf(limit.capacity()), String.valueOf(limit.perSecond()));
        return wait == null ? 0 : wait;
    }

    @SuppressWarnings("unused")
    private long failOpen(String key, RateLimitPolicy.Limit limit, Throwable t) {
        log.warn("Rate limiter unavailable ({}); allowing request", t.toString());
        return 0;
    }
}
