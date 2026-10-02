package com.arthadhruva.riskengine.billing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Meters a successful call of the annotated endpoint as one unit of {@link #value()} usage for the
 * caller's organization (see MeteringAspect). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Metered {

    /** The usage metric, e.g. SCORE_CALL, CVAR_SIMULATION. */
    String value();
}
