package com.arthadhruva.riskengine.event;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for side effects that must not slow down (or fail) the request that caused
 * them, e.g. creating notifications. Three things make it safe rather than merely async:
 * <ul>
 *   <li>{@link #tenantPropagating}: {@link TenantContext} is a ThreadLocal, so without copying it
 *       onto the worker thread every async query would run with no tenant, and Postgres row-level
 *       security would (correctly) return/accept nothing;</li>
 *   <li>a bounded queue with CallerRuns back-pressure: a burst slows producers down instead of
 *       growing memory without limit or silently dropping work;</li>
 *   <li>listeners run AFTER_COMMIT (see NotificationEventListener), never for a rolled-back change.</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    static TaskDecorator tenantPropagating() {
        return runnable -> {
            Long tenantId = TenantContext.getOptional().orElse(null);
            return () -> {
                try {
                    if (tenantId != null) {
                        TenantContext.set(tenantId);
                    }
                    runnable.run();
                } finally {
                    TenantContext.clear();
                }
            };
        };
    }

    @Bean(name = "eventExecutor")
    public Executor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("event-");
        executor.setTaskDecorator(tenantPropagating());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("Async event listener {} failed", method.getName(), ex);
    }
}
