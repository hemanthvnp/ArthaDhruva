package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The {@code security} module's façade for {@link LoginAttempt} -- {@link LoginAttemptRepository}
 * is private to this package. Reads the current tenant from {@link TenantContext} itself (rather
 * than taking it as a parameter) since a login attempt's tenant tag is a soft label for admin
 * convenience, not a security boundary (see {@link LoginAttempt}'s class doc) -- it's `null`
 * whenever the org slug on the request couldn't be resolved, which is exactly the enumeration
 * signal worth keeping either way.
 */
@Service
public class LoginAttemptService {

    private final LoginAttemptRepository loginAttemptRepository;

    public LoginAttemptService(LoginAttemptRepository loginAttemptRepository) {
        this.loginAttemptRepository = loginAttemptRepository;
    }

    public void record(String username, boolean success) {
        loginAttemptRepository.save(
                new LoginAttempt(TenantContext.getOptional().orElse(null), username, success, Instant.now()));
    }

    public Page<LoginAttempt> recentForTenant(Long tenantId, Pageable pageable) {
        return loginAttemptRepository.findAllByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }
}
