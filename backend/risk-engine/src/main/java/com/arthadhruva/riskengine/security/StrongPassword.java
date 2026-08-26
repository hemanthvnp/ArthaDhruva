package com.arthadhruva.riskengine.security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Applied to every field that sets a password (creation, self-service change, admin reset). */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPasswordValidator.class)
public @interface StrongPassword {
    String message() default "does not meet the password policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
