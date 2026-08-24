package com.arthadhruva.riskengine.audit;

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
 */
@RestControllerAdvice
public class ValidationAuditAdvice {

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

        auditEventWriter.write(new ModelInvocationEvent(
                endpoint,
                safeWrite(ex.getBindingResult().getTarget()),
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

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
