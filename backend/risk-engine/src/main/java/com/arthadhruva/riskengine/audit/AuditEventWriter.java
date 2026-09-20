package com.arthadhruva.riskengine.audit;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * The {@code audit} module's façade for {@link ModelInvocationEvent} -- {@link
 * ModelInvocationEventRepository} is private to this package. The only place {@link
 * ModelInvocationEventRepository#save} is called -- both {@link AuditAspect} and {@link
 * ValidationAuditAdvice} write through here rather than to the repository directly, so the
 * resilience annotations below actually apply (they're proxy-based; a caller in a *different*
 * bean is required for Spring AOP to intercept the call, so inlining this into either caller class
 * as a private method would silently disable them). {@link AuditLogController} reads through here
 * too, on the same class, since audit is a small enough module not to warrant a second service
 * just to separate the read side from the write side.
 *
 * Fails open: a circuit-open or Postgres-error fallback logs and returns normally, matching the
 * fail-open behavior the callers used to implement individually before this existed.
 */
@Service
public class AuditEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditEventWriter.class);

    private final ModelInvocationEventRepository repository;

    public AuditEventWriter(ModelInvocationEventRepository repository) {
        this.repository = repository;
    }

    @CircuitBreaker(name = "postgres", fallbackMethod = "onWriteFailure")
    @Retry(name = "postgres")
    @Bulkhead(name = "postgres")
    public void write(ModelInvocationEvent event) {
        repository.save(event);
    }

    private void onWriteFailure(ModelInvocationEvent event, Throwable t) {
        log.warn("Failed to persist audit event for {} (circuit open or DB error)", event.getEndpoint(), t);
    }

    public Page<ModelInvocationEvent> recentForTenant(Long tenantId, Pageable pageable) {
        return repository.findAllByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }
}
