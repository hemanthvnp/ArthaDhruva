package com.arthadhruva.riskengine.audit;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * The {@code audit} module's facade for {@link ModelInvocationEvent}: the only place events are saved,
 * so the resilience annotations (proxy-based, hence a separate bean from the callers) always apply.
 *
 * <p>Fails open -- a Postgres outage must not take scoring down -- but never silently: every failed write
 * increments {@code audit_write_failures_total}, which the Prometheus rule {@code AuditWritesFailing}
 * turns into an alert. Availability over completeness is a stated, observable trade-off here, not an
 * accident.
 */
@Service
public class AuditEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditEventWriter.class);

    private final ModelInvocationEventRepository repository;
    private final Counter failures;

    public AuditEventWriter(ModelInvocationEventRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.failures = Counter.builder("audit.write.failures")
                .description("Audit events that could not be persisted")
                .register(meterRegistry);
    }

    @CircuitBreaker(name = "postgres", fallbackMethod = "onWriteFailure")
    @Retry(name = "postgres")
    @Bulkhead(name = "postgres")
    public void write(ModelInvocationEvent event) {
        repository.save(event);
    }

    @SuppressWarnings("unused")
    private void onWriteFailure(ModelInvocationEvent event, Throwable t) {
        failures.increment();
        log.error("Failed to persist audit event for {} (circuit open or DB error)", event.getEndpoint(), t);
    }

    public Page<ModelInvocationEvent> recentForTenant(Long tenantId, Pageable pageable) {
        return repository.findAllByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }
}
