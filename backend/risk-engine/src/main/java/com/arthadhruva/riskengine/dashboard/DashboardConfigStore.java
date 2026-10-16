package com.arthadhruva.riskengine.dashboard;

import com.arthadhruva.riskengine.cache.CacheService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Write-behind storage for dashboard layouts.
 *
 * <p>A layout is saved on every rearrangement, several times in a few seconds while someone drags widgets
 * about, and only the last one matters. A save is therefore acknowledged once it is in this replica's buffer
 * and in the shared cache, and written to Postgres a moment later: the saves of one user inside a flush
 * interval collapse into one statement, and the request never waits on the database.
 *
 * <p>What that costs, and why it is acceptable <em>here</em>: a replica killed outright loses the layouts
 * it had not flushed yet (at most one interval; a normal shutdown flushes first). That is fine for a
 * widget arrangement and would not be for a score, a case or a usage count, which is why nothing else is
 * written this way.
 *
 * <p>Reads see the latest save at once: this replica's buffer first, then the cache (which every save
 * updates, so another replica sees it too), then the table. Each buffered save carries the time it was
 * made and the upsert refuses to replace a newer row, so two replicas flushing the same user's saves in
 * either order leave the later one.
 */
@Component
class DashboardConfigStore {

    private static final Logger log = LoggerFactory.getLogger(DashboardConfigStore.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private record Key(Long tenantId, String username) {
    }

    private record Pending(String widgetsJson, Instant writtenAt) {
    }

    private final JdbcTemplate jdbc;
    private final CacheService cache;
    private final Map<Key, Pending> pending = new ConcurrentHashMap<>();
    private final Counter flushFailures;

    DashboardConfigStore(JdbcTemplate jdbc, CacheService cache, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.flushFailures = Counter.builder("dashboard.config.flush.failures")
                .description("Buffered dashboard layouts that could not be written yet").register(meters);
        Gauge.builder("dashboard.config.pending", pending, Map::size)
                .description("Dashboard layouts saved but not yet written to the database").register(meters);
    }

    /** Accepts a save: buffered and visible to readers now, in the table within a flush interval. */
    void save(Long tenantId, String username, String widgetsJson) {
        pending.put(new Key(tenantId, username), new Pending(widgetsJson, Instant.now()));
        cache.put(cacheKey(tenantId, username), widgetsJson, CACHE_TTL);
    }

    /** The user's stored layout as JSON; the tenant context of the caller must be set. */
    Optional<String> find(Long tenantId, String username) {
        Pending buffered = pending.get(new Key(tenantId, username));
        if (buffered != null) {
            return Optional.of(buffered.widgetsJson());
        }
        Optional<String> cached = cache.get(cacheKey(tenantId, username), String.class);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<String> stored = jdbc.query("SELECT widgets FROM dashboard_config WHERE tenant_id = ? AND username = ?",
                (rs, i) -> rs.getString(1), tenantId, username).stream().findFirst();
        stored.ifPresent(json -> cache.put(cacheKey(tenantId, username), json, CACHE_TTL));
        return stored;
    }

    /** Writes what is buffered. A save that fails stays buffered for the next pass. */
    @Scheduled(fixedDelayString = "${dashboard.flush-ms:2000}")
    synchronized void flush() {
        for (Map.Entry<Key, Pending> entry : List.copyOf(pending.entrySet())) {
            Key key = entry.getKey();
            Pending save = entry.getValue();
            TenantContext.set(key.tenantId());
            try {
                jdbc.update("INSERT INTO dashboard_config (tenant_id, username, widgets, updated_at) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (tenant_id, username) DO UPDATE SET widgets = EXCLUDED.widgets, updated_at = EXCLUDED.updated_at "
                                + "WHERE dashboard_config.updated_at <= EXCLUDED.updated_at",
                        key.tenantId(), key.username(), save.widgetsJson(), Timestamp.from(save.writtenAt()));
                // Only if it is still the save just written: a newer one made meanwhile stays for the next pass.
                pending.remove(key, save);
            } catch (RuntimeException e) {
                flushFailures.increment();
                log.warn("Could not write the dashboard layout of {} (tenant {}); it stays buffered", key.username(), key.tenantId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    /** Tenant-prefixed, like every cache key: the cache is a scoping surface of its own, apart from Postgres. */
    private static String cacheKey(Long tenantId, String username) {
        return "dashboard:" + tenantId + ":" + username;
    }
}
