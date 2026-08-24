package com.arthadhruva.riskengine.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SR 11-7-style audit trail: wraps every model-serving controller method (pointcut matches any
 * class ending in "Controller" under this package, so new endpoints are covered automatically)
 * and persists one immutable {@link ModelInvocationEvent} row per call -- request, response,
 * timing, and outcome. Persistence failures are logged and swallowed: the audit trail must never
 * become a way to break the actual scoring/forecast response (a deliberate fail-open choice).
 *
 * Bean-validation ({@code @Valid}) rejections happen before the controller method -- and
 * therefore this proxy's advice -- is ever invoked, so those are captured separately by
 * {@link ValidationAuditAdvice}.
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final ModelInvocationEventRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditAspect(ModelInvocationEventRepository repository) {
        this.repository = repository;
    }

    @Around("execution(* com.arthadhruva.riskengine..*Controller.*(..))")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        String endpoint = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        String requestJson = safeWrite(namedArgs(joinPoint));
        long start = System.nanoTime();

        try {
            Object result = joinPoint.proceed();
            persist(endpoint, requestJson, safeWrite(result), true, null, elapsedMs(start));
            return result;
        } catch (Throwable ex) {
            persist(endpoint, requestJson, null, false, ex.getMessage(), elapsedMs(start));
            throw ex;
        }
    }

    private Map<String, Object> namedArgs(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            named.put(paramNames != null && i < paramNames.length ? paramNames[i] : "arg" + i, args[i]);
        }
        return named;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void persist(String endpoint, String requestJson, String responseJson,
                          boolean success, String errorMessage, long latencyMs) {
        try {
            repository.save(new ModelInvocationEvent(
                    endpoint, requestJson, responseJson, success, errorMessage, Instant.now(), latencyMs));
        } catch (Exception persistFailure) {
            log.warn("Failed to persist audit event for {}", endpoint, persistFailure);
        }
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
