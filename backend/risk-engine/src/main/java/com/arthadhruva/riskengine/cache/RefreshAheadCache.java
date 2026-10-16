package com.arthadhruva.riskengine.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Refresh-ahead on top of {@link CacheService}, for values that are expensive to compute and read often.
 *
 * <p>Plain cache-aside makes one unlucky caller pay for the recomputation every time an entry expires. Here
 * an entry read in the last {@link #REFRESH_FRACTION} of its life is returned as it is and recomputed in the
 * background, so a key that keeps being read never expires under a caller. A key nobody reads is not
 * refreshed and simply expires; the first read after that loads it, as cache-aside would.
 *
 * <p>One refresh per key at a time on this replica. The background work runs on the event executor, which
 * carries the tenant across (the loaders are tenant-scoped) and, when saturated, runs the refresh on the
 * caller instead of queueing without limit. A failed refresh leaves the old value to expire.
 */
@Component
public class RefreshAheadCache {

    private static final Logger log = LoggerFactory.getLogger(RefreshAheadCache.class);
    static final double REFRESH_FRACTION = 0.2;

    private final CacheService cache;
    private final Executor executor;
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final Counter refreshes;

    public RefreshAheadCache(CacheService cache, @Qualifier("eventExecutor") Executor executor, MeterRegistry meters) {
        this.cache = cache;
        this.executor = executor;
        this.refreshes = Counter.builder("cache.refresh.ahead").description("Cache entries recomputed before they expired")
                .register(meters);
    }

    /** The cached value, loading it when absent and refreshing it in the background when it is about to expire. */
    public <T> T get(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        Optional<T> cached = cache.get(key, type);
        if (cached.isEmpty()) {
            T loaded = loader.get();
            cache.put(key, loaded, ttl);
            return loaded;
        }
        Optional<Duration> left = cache.remainingTtl(key);
        if (left.isPresent() && left.get().toMillis() < ttl.toMillis() * REFRESH_FRACTION && refreshing.add(key)) {
            try {
                executor.execute(() -> refresh(key, ttl, loader));
            } catch (RuntimeException e) {
                refreshing.remove(key);
            }
        }
        return cached.get();
    }

    private <T> void refresh(String key, Duration ttl, Supplier<T> loader) {
        try {
            cache.put(key, loader.get(), ttl);
            refreshes.increment();
        } catch (RuntimeException e) {
            log.warn("Refresh-ahead of cache key {} failed; the current value stays until it expires", key, e);
        } finally {
            refreshing.remove(key);
        }
    }
}
