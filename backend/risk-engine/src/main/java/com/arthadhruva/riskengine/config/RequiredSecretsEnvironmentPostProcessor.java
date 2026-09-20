package com.arthadhruva.riskengine.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;
import java.util.Map;

/**
 * Fails startup immediately when the {@code production} profile is active and a secret this app
 * otherwise silently generates a random value for (see {@code JwtService}/{@code
 * TotpSecretCipher}) is unset. Those fallbacks are deliberately convenient for local/dev -- a
 * fresh checkout should just run -- but in production, a random JWT/TOTP key regenerated on every
 * restart silently invalidates every session and every enrolled 2FA secret, which should be a
 * loud startup failure, not a warning log an operator might not be watching (design decision 5).
 *
 * <p>Runs as an {@link EnvironmentPostProcessor}, registered via {@code META-INF/spring.factories}
 * rather than as a regular bean, specifically so the check happens before the application context
 * exists at all -- earlier than any bean (including the ones with the fallback behavior this
 * guards against) is created.
 */
@SuppressWarnings("deprecation") // still the documented, functioning SPI in Spring Boot 4.1.1; no stable replacement to migrate to yet
public class RequiredSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PRODUCTION_PROFILE = "production";

    private static final List<String> REQUIRED_IN_PRODUCTION = List.of(
            "jwt.secret",
            "totp.encryption-key"
    );

    /** Development defaults that must never survive into production (property -> its shipped default). */
    private static final Map<String, String> DEV_DEFAULTS = Map.of(
            "spring.datasource.password", "arthadhruva_app",
            "webhook.worker.db-password", "arthadhruva_worker",
            "spring.flyway.password", "arthadhruva",
            "spring.neo4j.authentication.password", "arthadhruva"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of(PRODUCTION_PROFILE))) {
            return;
        }

        List<String> missing = REQUIRED_IN_PRODUCTION.stream()
                .filter(key -> isBlank(environment.getProperty(key)))
                .toList();

        List<String> defaulted = DEV_DEFAULTS.entrySet().stream()
                .filter(e -> e.getValue().equals(environment.getProperty(e.getKey())))
                .map(Map.Entry::getKey).sorted().toList();
        if (!defaulted.isEmpty()) {
            throw new IllegalStateException("Development-default credential(s) still set under the 'production' profile: "
                    + defaulted + ". Provide real secrets (see scripts/gen-secrets.sh and docker-compose.prod.yml).");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required secret(s) for the 'production' profile: " + missing
                            + ". These fall back to a randomly generated value outside 'production', "
                            + "but a random value in production silently invalidates sessions/2FA "
                            + "secrets on every restart -- set them explicitly before starting.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
