package com.arthadhruva.riskengine.audit;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts validation-rejection exceptions into a proper 400 response. Spring MVC validates two
 * distinct things two different ways, so this handles two different exception types:
 *
 * <p>{@code @Valid @RequestBody} failures (e.g. LoanFeatures, CvarRequest) throw
 * {@link MethodArgumentNotValidException}, resolved while Spring MVC builds the controller
 * method's argument list -- <em>before</em> the AOP proxy's method invocation happens -- so this
 * is genuinely the only place they're captured for the audit trail; {@link AuditAspect} never
 * sees them at all, since {@code joinPoint.proceed()} is never reached.
 *
 * <p>Constrained {@code @RequestParam}/{@code @PathVariable} method parameters (e.g.
 * SegmentGraphController's {@code maxHops}, via class-level {@code @Validated}) throw
 * {@link ConstraintViolationException} instead -- but this validation runs as a method
 * interceptor *inside* the same AOP proxy chain, so unlike the above, {@link AuditAspect}'s
 * {@code @Around} advice already sees and records this failure via its own catch block (with
 * richer detail -- the actual request args -- than this handler could reconstruct). This handler
 * therefore only shapes the HTTP response for that case and does not write a second, redundant
 * audit event.
 *
 * <p>Also handles {@link RequestNotPermitted} (Resilience4j's rate-limiter rejection, e.g. on
 * {@code /login}) -- without a handler here it would fall through to Spring's default 500, which
 * is the wrong signal for "you're being rate-limited," not "the server is broken."
 *
 * <p>{@link #handleValidationFailure} redacts the request body for the same credential-carrying
 * controllers {@code AuditAspect} excludes: a rejected {@code @StrongPassword} field (too short,
 * no digit, etc.) is still a real, non-blank password the caller typed, and it must never end up
 * in the audit trail just because it failed the strength check.
 */
@RestControllerAdvice
public class ValidationAuditAdvice {

    /** Same three controllers AuditAspect excludes -- their request DTOs can carry a raw
     * password, so a validation failure on one of them must never serialize the target object
     * into the audit trail (field-level messages like "newPassword: too short" are still fine). */
    private static final Set<String> CREDENTIAL_CARRYING_CONTROLLERS =
            Set.of("AuthController", "AdminUserController", "AccountController");

    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationAuditAdvice(AuditEventWriter auditEventWriter) {
        this.auditEventWriter = auditEventWriter;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationFailure(MethodArgumentNotValidException ex) {
        Method method = ex.getParameter().getMethod();
        String endpoint = method != null
                ? method.getDeclaringClass().getSimpleName() + "." + method.getName()
                : "unknown";

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        String requestJson = method != null && CREDENTIAL_CARRYING_CONTROLLERS.contains(method.getDeclaringClass().getSimpleName())
                ? null
                : safeWrite(ex.getBindingResult().getTarget());

        auditEventWriter.write(new ModelInvocationEvent(
                endpoint,
                requestJson,
                null,
                false,
                fieldErrors.toString(),
                Instant.now(),
                0L));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(RequestNotPermitted ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Too many requests -- please slow down.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
