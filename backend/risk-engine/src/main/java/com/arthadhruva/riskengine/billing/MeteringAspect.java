package com.arthadhruva.riskengine.billing;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Counts every successful call to a {@link Metered} endpoint against the caller's organization. It
 * replaced metering that hung off the "loan scored" event, which only fired for scores carrying a loan
 * id and a successful database write -- so most scoring and every other model endpoint went unbilled.
 */
@Aspect
@Component
public class MeteringAspect {

    private final UsageService usage;

    public MeteringAspect(UsageService usage) {
        this.usage = usage;
    }

    @AfterReturning(pointcut = "@annotation(metered)", returning = "result")
    public void meter(Metered metered, Object result) {
        if (result instanceof ResponseEntity<?> response && !response.getStatusCode().is2xxSuccessful()) {
            return;
        }
        TenantContext.getOptional().ifPresent(tenantId -> usage.record(tenantId, metered.value()));
    }
}
