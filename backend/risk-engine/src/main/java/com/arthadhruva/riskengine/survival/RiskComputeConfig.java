package com.arthadhruva.riskengine.survival;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pools for the CPU-heavy risk computations (portfolio projections, Monte Carlo), kept apart from
 * the request threads so a long run cannot starve interactive scoring.
 *
 * <ul>
 *   <li>{@code riskComputeExecutor}: the workers that do the arithmetic. Sized below the core count
 *       (one core is left for request handling) and at most {@code risk.compute.max-threads}; work
 *       queues here rather than oversubscribing the CPU.</li>
 *   <li>{@code riskRunExecutor}: coordinates long-running jobs, a few at a time. Its queue is short and
 *       it rejects when full, so a burst of requests is refused with a clear "busy" instead of piling
 *       up minutes of work nobody is waiting for any more.</li>
 * </ul>
 * Both are instrumented (pool size, active threads, queue depth) under the {@code executor.*} metrics.
 */
@Configuration
public class RiskComputeConfig {

    @Bean(name = "riskComputeExecutor", destroyMethod = "shutdownNow")
    ExecutorService riskComputeExecutor(@Value("${risk.compute.threads:0}") int configured,
                                        @Value("${risk.compute.max-threads:4}") int maxThreads, MeterRegistry meters) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads(configured, maxThreads), threads(configured, maxThreads),
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), named("risk-compute-"));
        return ExecutorServiceMetrics.monitor(meters, pool, "riskCompute");
    }

    @Bean(name = "riskRunExecutor", destroyMethod = "shutdownNow")
    ExecutorService riskRunExecutor(@Value("${risk.runs.concurrent:2}") int concurrent,
                                    @Value("${risk.runs.queue:8}") int queue, MeterRegistry meters) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(concurrent, concurrent, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), named("risk-run-"), new ThreadPoolExecutor.AbortPolicy());
        return ExecutorServiceMetrics.monitor(meters, pool, "riskRun");
    }

    /** Worker threads for a configured count, or one fewer than the cores (at least one) when it is 0. */
    public static int threads(int configured, int maxThreads) {
        return configured > 0 ? configured : Math.max(1, Math.min(maxThreads, Runtime.getRuntime().availableProcessors() - 1));
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
