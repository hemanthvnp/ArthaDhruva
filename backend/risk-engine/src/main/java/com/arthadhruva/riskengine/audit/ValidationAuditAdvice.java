package com.arthadhruva.riskengine.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Captures {@code @Valid} rejections into the same audit trail {@link AuditAspect} writes to.
 * Bean-validation failures are resolved while Spring MVC builds the controller method's argument
 * list -- before the AOP proxy's method invocation happens -- so they never reach AuditAspect's
 * {@code @Around} advice and need this separate exception-handler-based path instead.
 */
@RestControllerAdvice
public class ValidationAuditAdvice {

    private static final Logger log = LoggerFactory.getLogger(ValidationAuditAdvice.class);

    private final ModelInvocationEventRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationAuditAdvice(ModelInvocationEventRepository repository) {
        this.repository = repository;
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

        try {
            repository.save(new ModelInvocationEvent(
                    endpoint,
                    safeWrite(ex.getBindingResult().getTarget()),
                    null,
                    false,
                    fieldErrors.toString(),
                    Instant.now(),
                    0L));
        } catch (Exception persistFailure) {
            log.warn("Failed to persist validation-failure audit event for {}", endpoint, persistFailure);
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
