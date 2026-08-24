package com.arthadhruva.riskengine.security;

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
    private final PasswordEncoder passwordEncoder;
    private final String configuredUsername;
    private final String configuredPassword;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           @Value("${admin.username}") String configuredUsername,
                           @Value("${admin.password}") String configuredPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }

        boolean generated = configuredPassword == null || configuredPassword.isBlank();
        String password = generated ? generatePassword() : configuredPassword;
        userRepository.save(new User(configuredUsername, passwordEncoder.encode(password), Role.ADMIN));

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

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
