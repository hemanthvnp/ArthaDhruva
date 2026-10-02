package com.arthadhruva.riskengine.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a controller (type) or a single handler method from the generic audit trail, for endpoints
 * whose requests or responses carry credentials (login, password reset, SSO) or that are high-frequency
 * reads of the caller's own data (notification polling). Declared next to the code it describes, so it
 * cannot drift out of sync the way a central exclusion list did. Field-level redaction in
 * {@link AuditRedactor} still applies everywhere else, as a second line of defense.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NotAudited {
}
