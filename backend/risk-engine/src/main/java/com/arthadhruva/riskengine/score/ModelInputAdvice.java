package com.arthadhruva.riskengine.score;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** A loan outside the model's domain is a 422 with a message per field -- the same shape as a validation
 * failure, so clients handle both the same way. */
@RestControllerAdvice
public class ModelInputAdvice {

    @ExceptionHandler(LoanInputValidator.InvalidLoanException.class)
    public ResponseEntity<Map<String, Object>> onInvalidLoan(LoanInputValidator.InvalidLoanException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("fields", e.getFields());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }
}
