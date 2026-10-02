package com.arthadhruva.riskengine.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Lets a model-serving endpoint tell the audit trail which model version produced its answer, without
 * the controller knowing anything about auditing beyond one call.
 */
public final class AuditContext {

    private static final String MODEL_VERSION = AuditContext.class.getName() + ".modelVersion";

    private AuditContext() {
    }

    /** Records the model version on the current request (no-op outside a request). */
    public static void modelVersion(String version) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(MODEL_VERSION, version);
        }
    }

    static String modelVersion(HttpServletRequest request) {
        return request == null ? null : (String) request.getAttribute(MODEL_VERSION);
    }
}
