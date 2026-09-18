package com.arthadhruva.riskengine.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private final int minLength;

    public StrongPasswordValidator(@Value("${auth.password-min-length}") int minLength) {
        this.minLength = minLength;
    }

    /** Pure policy check, reused outside the annotation-driven path by AdminUserController#createUser,
     * where a password is only conditionally required (ADMIN/ANALYST, not CLIENT) -- Bean
     * Validation has no clean way to express "required only for these roles" at the record-field
     * level, so that case is validated manually against this same rule instead. */
    public boolean isStrong(String password) {
        if (password == null) {
            return false;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        return password.length() >= minLength && hasLetter && hasDigit;
    }

    public String policyMessage() {
        return "must be at least " + minLength + " characters and contain a letter and a digit";
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // let @NotBlank own the null/blank case
        }
        if (isStrong(value)) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(policyMessage()).addConstraintViolation();
        return false;
    }
}
