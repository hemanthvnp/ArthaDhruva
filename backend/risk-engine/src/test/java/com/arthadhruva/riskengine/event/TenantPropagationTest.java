package com.arthadhruva.riskengine.event;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantPropagationTest {

    private ThreadPoolTaskExecutor executor(boolean decorated) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        if (decorated) {
            e.setTaskDecorator(AsyncConfig.tenantPropagating());
        }
        e.initialize();
        return e;
    }

    @Test
    void tenantIsVisibleOnTheWorkerThreadAndCleanedUpAfterwards() throws Exception {
        ThreadPoolTaskExecutor e = executor(true);
        TenantContext.set(42L);
        try {
            CompletableFuture<Long> seen = new CompletableFuture<>();
            e.execute(() -> seen.complete(TenantContext.getOptional().orElse(null)));
            assertEquals(42L, seen.get(2, TimeUnit.SECONDS));

            TenantContext.clear();
            CompletableFuture<Boolean> leaked = new CompletableFuture<>();
            e.execute(() -> leaked.complete(TenantContext.getOptional().isPresent()));
            assertTrue(!leaked.get(2, TimeUnit.SECONDS), "a pooled thread must not keep the previous task's tenant");
        } finally {
            TenantContext.clear();
            e.shutdown();
        }
    }

    @Test
    void withoutTheDecoratorTheTenantIsLost() throws Exception {
        ThreadPoolTaskExecutor e = executor(false);
        TenantContext.set(42L);
        try {
            CompletableFuture<Boolean> present = new CompletableFuture<>();
            e.execute(() -> present.complete(TenantContext.getOptional().isPresent()));
            assertTrue(!present.get(2, TimeUnit.SECONDS));
        } finally {
            TenantContext.clear();
            e.shutdown();
        }
    }
}
