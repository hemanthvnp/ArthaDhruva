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

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // let @NotBlank own the null/blank case
        }
        boolean hasLetter = value.chars().anyMatch(Character::isLetter);
        boolean hasDigit = value.chars().anyMatch(Character::isDigit);
        if (value.length() >= minLength && hasLetter && hasDigit) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                "must be at least " + minLength + " characters and contain a letter and a digit")
                .addConstraintViolation();
        return false;
    }
}
