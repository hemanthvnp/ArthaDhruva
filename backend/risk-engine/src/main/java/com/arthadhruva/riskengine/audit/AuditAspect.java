package com.arthadhruva.riskengine.audit;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit trail for every API controller call (SR 11-7-style): who called what, with which inputs, what
 * came back, which model version answered, and how long it took. One append-only
 * {@link ModelInvocationEvent} per call, written through {@link AuditEventWriter}.
 *
 * <p>What is recorded:
 * <ul>
 *   <li>request arguments, redacted by {@link AuditRedactor} (credentials never reach the table);</li>
 *   <li>the response body for state-changing calls only -- reads record that they happened and their
 *       outcome, not a copy of the data read (which would duplicate it and bloat the trail);</li>
 *   <li>the actor, HTTP method and path, status, client address, and the model version when the
 *       endpoint served a model (see {@link AuditContext}).</li>
 * </ul>
 * Controllers or methods that carry credentials, or that are high-frequency reads of the caller's own
 * data, opt out with {@link NotAudited} where they are declared.
 */
@Aspect
@Component
public class AuditAspect {

    static final int MAX_REQUEST_CHARS = 16_384;
    static final int MAX_RESPONSE_CHARS = 16_384;

    private final AuditEventWriter auditEventWriter;
    private final AuditRedactor redactor;

    public AuditAspect(AuditEventWriter auditEventWriter, AuditRedactor redactor) {
        this.auditEventWriter = auditEventWriter;
        this.redactor = redactor;
    }

    @Around("execution(* com.arthadhruva.riskengine..*Controller.*(..)) "
            + "&& @within(org.springframework.web.bind.annotation.RestController) "
            + "&& !@within(com.arthadhruva.riskengine.audit.NotAudited) "
            + "&& !@annotation(com.arthadhruva.riskengine.audit.NotAudited)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        String endpoint = joinPoint.getSignature().getDeclaringType().getSimpleName() + "." + joinPoint.getSignature().getName();
        HttpServletRequest http = currentRequest();
        boolean read = http != null && ("GET".equals(http.getMethod()) || "HEAD".equals(http.getMethod()));
        String requestJson = redactor.toJson(namedArgs(joinPoint), MAX_REQUEST_CHARS);
        // Captured before proceeding: the controller may clear the tenant context itself (public
        // endpoints that set it from a token), and the actor must be who made the request.
        Long tenantAtStart = TenantContext.getOptional().orElse(null);
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            int status = result instanceof ResponseEntity<?> response ? response.getStatusCode().value() : 200;
            persist(tenantAtStart, endpoint, http, requestJson, read ? null : redactor.toJson(result, MAX_RESPONSE_CHARS),
                    status < 400, null, status, start);
            return result;
        } catch (Throwable ex) {
            String message = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
            persist(tenantAtStart, endpoint, http, requestJson, null, false,
                    message.length() > 500 ? message.substring(0, 500) : message, 500, start);
            throw ex;
        }
    }

    /** Framework plumbing arguments are not request data; an upload is recorded by name and size. */
    private Map<String, Object> namedArgs(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof jakarta.servlet.ServletRequest || arg instanceof jakarta.servlet.ServletResponse
                    || arg instanceof Authentication || arg instanceof Principal) {
                continue;
            }
            if (arg instanceof MultipartFile file) {
                arg = Map.of("filename", String.valueOf(file.getOriginalFilename()), "sizeBytes", file.getSize(),
                        "contentType", String.valueOf(file.getContentType()));
            }
            named.put(names != null && i < names.length ? names[i] : "arg" + i, arg);
        }
        return named;
    }

    private void persist(Long tenantId, String endpoint, HttpServletRequest http, String requestJson,
                         String responseJson, boolean success, String errorMessage, int status, long startNanos) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth == null || !auth.isAuthenticated() ? null : auth.getName();
        auditEventWriter.write(new ModelInvocationEvent(tenantId, endpoint, requestJson, responseJson, success,
                errorMessage, Instant.now(), (System.nanoTime() - startNanos) / 1_000_000)
                .withContext(actor, http == null ? null : http.getMethod(), http == null ? null : http.getRequestURI(),
                        status, http == null ? null : http.getRemoteAddr(), AuditContext.modelVersion(http)));
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
