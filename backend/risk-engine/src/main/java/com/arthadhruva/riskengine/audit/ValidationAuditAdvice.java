package com.arthadhruva.riskengine.audit;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns request-validation failures into a 400 and records them in the audit trail.
 *
 * <p>{@code @Valid @RequestBody} failures are raised while Spring MVC resolves arguments, before
 * {@link AuditAspect} runs, so this is the only place they are captured. Payloads are redacted by
 * {@link AuditRedactor}, and nothing is recorded for handlers marked {@link NotAudited}: a rejected
 * password (too short, say) is still a real password someone typed.
 *
 * <p>Constraint violations on method parameters are raised inside the audited proxy (AuditAspect
 * records them), so this only shapes their response.
 */
@RestControllerAdvice
public class ValidationAuditAdvice {

    private final AuditEventWriter auditEventWriter;
    private final AuditRedactor redactor;

    public ValidationAuditAdvice(AuditEventWriter auditEventWriter, AuditRedactor redactor) {
        this.auditEventWriter = auditEventWriter;
        this.redactor = redactor;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationFailure(MethodArgumentNotValidException ex, HttpServletRequest http) {
        Method method = ex.getParameter().getMethod();
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        if (method != null && !notAudited(method)) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            auditEventWriter.write(new ModelInvocationEvent(
                    TenantContext.getOptional().orElse(null),
                    method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                    redactor.toJson(ex.getBindingResult().getTarget(), AuditAspect.MAX_REQUEST_CHARS),
                    null, false, fieldErrors.toString(), Instant.now(), 0L)
                    .withContext(auth == null ? null : auth.getName(), http.getMethod(), http.getRequestURI(), 400,
                            http.getRemoteAddr(), null));
        }
        return badRequest(fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return badRequest(fieldErrors);
    }

    /** Unparseable JSON or a wrong type is the caller's error: 400, never a 500. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleUnreadable(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for parameter '" + mismatch.getName() + "'"
                : "Malformed request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static boolean notAudited(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, NotAudited.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), NotAudited.class);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
