package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only (enforced by SecurityConfig's /admin/** rule): the credential-free login-activity
 * log for the caller's own organization, mirroring AuditLogController's shape exactly. */
@RestController
public class LoginAttemptController {

    private static final int MAX_LIMIT = 500;

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptController(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @GetMapping("/admin/login-attempts")
    public List<LoginAttempt> recent(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return loginAttemptService.recentForTenant(TenantContext.get(), PageRequest.of(0, bounded)).getContent();
    }
}
