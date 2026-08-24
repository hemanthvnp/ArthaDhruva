package com.arthadhruva.riskengine.cache;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Thin JSON cache over Redis (the "Online Feature Store" from the project's backend spec).
 * {@code get}/{@code put} are circuit-breaker/retry/bulkhead-protected against Redis outages
 * (via the {@code redis} Resilience4j instance, configured in application.properties) -- a
 * fallback method logs and degrades to "no cache" rather than propagating the failure, since
 * the cache must never be a way to break {@code /score} or {@code /regime-forecast}. JSON
 * (de)serialization errors are handled separately, inline, and deliberately do NOT count toward
 * the circuit breaker: a bad payload is a caller/data bug, not a sign Redis itself is unhealthy.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "putFallback")
    @Retry(name = "redis")
    @Bulkhead(name = "redis")
    public <T> void put(String key, T value, Duration ttl) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize cache value for key {}", key, e);
            return;
        }
        redisTemplate.opsForValue().set(key, json, ttl);
    }

    private <T> void putFallback(String key, T value, Duration ttl, Throwable t) {
        log.warn("Failed to write cache key {} (circuit open or Redis error)", key, t);
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "getFallback")
    @Retry(name = "redis")
    @Bulkhead(name = "redis")
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Failed to deserialize cache value for key {}", key, e);
            return Optional.empty();
        }
    }

    private <T> Optional<T> getFallback(String key, Class<T> type, Throwable t) {
        log.warn("Failed to read cache key {} (circuit open or Redis error)", key, t);
        return Optional.empty();
    }
}
