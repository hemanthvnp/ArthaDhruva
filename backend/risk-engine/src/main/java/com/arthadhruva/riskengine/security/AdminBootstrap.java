package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Creates the first ADMIN account on a fresh database (no self-registration exists, and none is
 * planned -- user provisioning for a risk-scoring platform should be controlled, not open).
 * {@code ADMIN_USERNAME}/{@code ADMIN_PASSWORD} set the bootstrap credentials explicitly (e.g.
 * for CI); otherwise a random password is generated and logged once, the same pattern tools like
 * Jenkins/Keycloak use for their own first-admin bootstrap.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 20;

    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final PasswordEncoder passwordEncoder;
    private final String configuredUsername;
    private final String configuredPassword;
    private final String platformAdminPassword;

    public AdminBootstrap(UserRepository userRepository, OrganizationService organizationService,
                           PasswordEncoder passwordEncoder,
                           @Value("${admin.username}") String configuredUsername,
                           @Value("${admin.password}") String configuredPassword,
                           @Value("${platform.admin-password:}") String platformAdminPassword) {
        this.platformAdminPassword = platformAdminPassword;
        this.userRepository = userRepository;
        this.organizationService = organizationService;
        this.passwordEncoder = passwordEncoder;
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // On a real deployment this row already exists (seeded by V2__create_organization_table.sql
        // against a pre-existing database); on a brand-new, entirely empty database (e.g. a fresh
        // CI/test run against a schema that's never had the seed data applied) it won't, so create
        // it here rather than assuming the migration's INSERT already ran.
        Organization legacyOrg = organizationService.findOrCreate(Organization.LEGACY_SLUG, "Legacy Organization");

        // Row-level security means "no users anywhere" is unknowable from the app role, so the
        // bootstrap check is scoped to the legacy tenant the admin is created in.
        TenantContext.set(legacyOrg.getId());
        try {
            bootstrapAdmin(legacyOrg);
        } finally {
            TenantContext.clear();
        }
        bootstrapPlatformAdmin();
    }

    private void bootstrapAdmin(Organization legacyOrg) {
        if (userRepository.count() > 0) {
            return;
        }

        boolean generated = configuredPassword == null || configuredPassword.isBlank();
        String password = generated ? generatePassword() : configuredPassword;
        userRepository.save(new User(legacyOrg, configuredUsername, passwordEncoder.encode(password), Role.ADMIN));

        if (generated) {
            log.warn("=======================================================================");
            log.warn(" Bootstrap admin account created (no users existed yet).");
            log.warn(" Username: {}", configuredUsername);
            log.warn(" Password: {}", password);
            log.warn(" Shown only once -- log in now and note it down.");
            log.warn("=======================================================================");
        } else {
            log.info("Bootstrap admin account '{}' created from ADMIN_USERNAME/ADMIN_PASSWORD.", configuredUsername);
        }
    }

    /** Creates the platform operator account when PLATFORM_ADMIN_PASSWORD is set (never otherwise). */
    private void bootstrapPlatformAdmin() {
        if (platformAdminPassword == null || platformAdminPassword.isBlank()) {
            return;
        }
        Organization platform = organizationService.findOrCreate("platform", "Platform Operations");
        TenantContext.set(platform.getId());
        try {
            if (userRepository.count() == 0) {
                userRepository.save(new User(platform, "platform-admin", passwordEncoder.encode(platformAdminPassword), Role.PLATFORM_ADMIN));
                log.info("Platform admin account 'platform-admin' created in organization 'platform'.");
            }
        } finally {
            TenantContext.clear();
        }
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
