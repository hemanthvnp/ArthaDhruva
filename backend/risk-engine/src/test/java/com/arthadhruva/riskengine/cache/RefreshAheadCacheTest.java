package com.arthadhruva.riskengine.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshAheadCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final CacheService cache = mock(CacheService.class);
    /** Background work is held here until the test runs it, so "returned first, refreshed after" is observable. */
    private final List<Runnable> background = new ArrayList<>();
    private final Executor executor = background::add;
    private final RefreshAheadCache refreshAhead = new RefreshAheadCache(cache, executor, new SimpleMeterRegistry());
    private final AtomicInteger loads = new AtomicInteger();

    private String load() {
        return "computed-" + loads.incrementAndGet();
    }

    @Test
    void aMissIsLoadedAndCached() {
        when(cache.get("k", String.class)).thenReturn(Optional.empty());

        assertEquals("computed-1", refreshAhead.get("k", String.class, TTL, this::load));
        verify(cache).put("k", "computed-1", TTL);
        assertEquals(0, background.size());
    }

    @Test
    void aFreshEntryIsReturnedWithoutLoading() {
        when(cache.get("k", String.class)).thenReturn(Optional.of("cached"));
        when(cache.remainingTtl("k")).thenReturn(Optional.of(Duration.ofMinutes(9)));

        assertEquals("cached", refreshAhead.get("k", String.class, TTL, this::load));
        assertEquals(0, loads.get());
        assertEquals(0, background.size());
    }

    @Test
    void anEntryAboutToExpireIsReturnedAsItIsAndRefreshedOnceInTheBackground() {
        when(cache.get("k", String.class)).thenReturn(Optional.of("cached"));
        when(cache.remainingTtl("k")).thenReturn(Optional.of(Duration.ofSeconds(90)));

        assertEquals("cached", refreshAhead.get("k", String.class, TTL, this::load));
        assertEquals("cached", refreshAhead.get("k", String.class, TTL, this::load), "a second reader does not start a second refresh");
        assertEquals(1, background.size());
        assertEquals(0, loads.get(), "no caller waited for the recomputation");

        background.remove(0).run();
        verify(cache).put("k", "computed-1", TTL);

        refreshAhead.get("k", String.class, TTL, this::load);
        assertEquals(1, background.size(), "once a refresh has finished the key can be refreshed again");
    }

    @Test
    void aFailedRefreshLeavesTheEntryAndDoesNotBlockTheNextOne() {
        when(cache.get("k", String.class)).thenReturn(Optional.of("cached"));
        when(cache.remainingTtl("k")).thenReturn(Optional.of(Duration.ofSeconds(30)));

        refreshAhead.get("k", String.class, TTL, () -> {
            throw new IllegalStateException("the source is down");
        });
        background.remove(0).run();
        verify(cache, never()).put(eq("k"), any(), any());

        refreshAhead.get("k", String.class, TTL, this::load);
        assertEquals(1, background.size());
    }

    @Test
    void whenTheCacheCannotSayHowLongIsLeftNothingIsRefreshed() {
        when(cache.get("k", String.class)).thenReturn(Optional.of("cached"));
        when(cache.remainingTtl("k")).thenReturn(Optional.empty());

        assertEquals("cached", refreshAhead.get("k", String.class, TTL, this::load));
        assertEquals(0, background.size());
    }
}
