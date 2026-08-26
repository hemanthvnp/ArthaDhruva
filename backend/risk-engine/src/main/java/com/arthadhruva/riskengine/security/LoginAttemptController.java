package com.arthadhruva.riskengine.security;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only (enforced by SecurityConfig's /admin/** rule): the credential-free login-activity
 * log, mirroring AuditLogController's shape exactly. */
@RestController
public class LoginAttemptController {

    private static final int MAX_LIMIT = 500;

    private final LoginAttemptRepository repository;

    public LoginAttemptController(LoginAttemptRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/admin/login-attempts")
    public List<LoginAttempt> recent(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository
                .findAll(PageRequest.of(0, bounded, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .getContent();
    }
}
