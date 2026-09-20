package com.arthadhruva.riskengine.audit;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SR 11-7-style audit trail: wraps every model-serving controller method (pointcut matches any
 * class ending in "Controller" under this package, so new endpoints are covered automatically)
 * and persists one immutable {@link ModelInvocationEvent} row per call -- request, response,
 * timing, and outcome, via {@link AuditEventWriter} (which owns the circuit-breaker/retry/
 * fail-open behavior so a Postgres outage can never become a way to break the actual
 * scoring/forecast response).
 *
 * {@code AuthController}, {@code AdminUserController}, {@code AccountController}, and
 * {@code ActivationController} are deliberately excluded: the first's request carries a raw
 * password and its response carries a live JWT; the others carry a raw password on several
 * endpoints (creation, admin reset, self-service change, invite activation). This aspect
 * serializes whatever it's given verbatim -- logging any of these into Postgres would mean
 * anyone with audit-trail read access could read plaintext credentials or hijack a session via a
 * logged token. Login attempts and user provisioning still need their own accountability trail,
 * just not a copy of the secret material; that's out of scope for this round (see each excluded
 * controller's own class doc).
 *
 * Bean-validation ({@code @Valid}) rejections happen before the controller method -- and
 * therefore this proxy's advice -- is ever invoked, so those are captured separately by
 * {@link ValidationAuditAdvice}.
 *
 * {@code AssistantController} is excluded too, for a different reason than the credential-carrying
 * controllers above: a free-text question and an LLM's free-text answer aren't the structured,
 * replayable request/response shape this audit trail is designed for, not a secrecy concern.
 */
@Aspect
@Component
public class AuditAspect {

    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditAspect(AuditEventWriter auditEventWriter) {
        this.auditEventWriter = auditEventWriter;
    }

    @Around("execution(* com.arthadhruva.riskengine..*Controller.*(..)) "
            + "&& !within(com.arthadhruva.riskengine.security.AuthController) "
            + "&& !within(com.arthadhruva.riskengine.security.AdminUserController) "
            + "&& !within(com.arthadhruva.riskengine.security.AccountController) "
            + "&& !within(com.arthadhruva.riskengine.security.ActivationController) "
            + "&& !within(com.arthadhruva.riskengine.assistant.AssistantController)")
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
        auditEventWriter.write(new ModelInvocationEvent(
                TenantContext.getOptional().orElse(null),
                endpoint, requestJson, responseJson, success, errorMessage, Instant.now(), latencyMs));
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
