package com.arthadhruva.riskengine.ratelimit;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed token buckets. A request names one or more buckets (its organization's, its own) and a
 * cost; it passes only if every bucket holds the cost, and then pays it from all of them. The whole
 * read-refill-check-consume step is one Lua script, which Redis runs atomically, so any number of app
 * instances share one accurate bucket per key (an in-process limiter gives each replica its own bucket,
 * silently multiplying the limit by the replica count), and a request refused by one bucket never
 * drains another. Time comes from Redis itself ({@code TIME}), not the callers, so clock skew between
 * instances cannot mis-refill. State expires on its own once a bucket would have fully refilled twice.
 *
 * <p>Fails open when Redis is unavailable (circuit breaker + fallback), consistent with the cache:
 * a rate limiter outage must not become an API outage.
 */
@Service
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** One bucket a request draws from. Keys of one request share a {@code {hash tag}}, so the script
     * stays valid on Redis Cluster, where all keys of a script must live in one slot. */
    public record Bucket(String key, RateLimitPolicy.Limit limit) {
    }

    /**
     * @param waitMillis 0 if the request may proceed, otherwise how long until it could
     * @param remaining  whole tokens left in the tightest bucket afterwards (-1 when unknown)
     */
    public record Decision(long waitMillis, long remaining) {
        public boolean allowed() {
            return waitMillis == 0;
        }
    }

    // KEYS: the buckets. ARGV: capacity and refill-per-second of each bucket in order, then the cost.
    // A cost above a bucket's capacity is charged as the whole bucket, so no request is unpayable.
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> BUCKETS = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local cost = tonumber(ARGV[#ARGV])
            local tokens = {}
            local wait = 0
            for i = 1, #KEYS do
              local cap = tonumber(ARGV[2 * i - 1])
              local rate = tonumber(ARGV[2 * i])
              local d = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
              local have = tonumber(d[1]) or cap
              local ts = tonumber(d[2]) or now
              tokens[i] = math.min(cap, have + math.max(0, now - ts) * rate / 1000)
              local need = math.min(cost, cap)
              if tokens[i] < need then
                wait = math.max(wait, math.ceil((need - tokens[i]) / rate * 1000))
              end
            end
            local remaining = -1
            for i = 1, #KEYS do
              local cap = tonumber(ARGV[2 * i - 1])
              local rate = tonumber(ARGV[2 * i])
              if wait == 0 then
                tokens[i] = tokens[i] - math.min(cost, cap)
              end
              redis.call('HSET', KEYS[i], 'tokens', tokens[i], 'ts', now)
              redis.call('PEXPIRE', KEYS[i], math.ceil(cap / rate * 2000) + 1000)
              if remaining < 0 or tokens[i] < remaining then
                remaining = math.floor(tokens[i])
              end
            end
            return {wait, remaining}
            """, List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "failOpen")
    public Decision acquire(List<Bucket> buckets, int cost) {
        List<String> keys = new ArrayList<>(buckets.size());
        Object[] args = new Object[2 * buckets.size() + 1];
        for (int i = 0; i < buckets.size(); i++) {
            keys.add(buckets.get(i).key());
            args[2 * i] = String.valueOf(buckets.get(i).limit().capacity());
            args[2 * i + 1] = String.valueOf(buckets.get(i).limit().perSecond());
        }
        args[args.length - 1] = String.valueOf(cost);
        List<?> result = redis.execute(BUCKETS, keys, args);
        if (result == null || result.size() < 2) {
            return new Decision(0, -1);
        }
        return new Decision(((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue());
    }

    @SuppressWarnings("unused")
    private Decision failOpen(List<Bucket> buckets, int cost, Throwable t) {
        log.warn("Rate limiter unavailable ({}); allowing request", t.toString());
        return new Decision(0, -1);
    }
}
