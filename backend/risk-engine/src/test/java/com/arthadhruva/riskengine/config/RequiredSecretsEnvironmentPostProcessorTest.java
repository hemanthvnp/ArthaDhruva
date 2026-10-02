package com.arthadhruva.riskengine.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that keeps a production instance from starting on generated or development secrets. It is
 * wired by a line in {@code META-INF/spring.factories}; if that line names the wrong interface the
 * guard is silently never run, which no other test would notice.
 */
class RequiredSecretsEnvironmentPostProcessorTest {

    private final RequiredSecretsEnvironmentPostProcessor guard = new RequiredSecretsEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication();

    private static MockEnvironment production() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment
                .withProperty("jwt.secret", "0123456789abcdef0123456789abcdef-production")
                .withProperty("totp.encryption-key", Base64.getEncoder().encodeToString(new byte[32]))
                .withProperty("spring.datasource.password", "a-real-app-password")
                .withProperty("webhook.worker.db-password", "a-real-worker-password")
                .withProperty("spring.flyway.password", "a-real-owner-password")
                .withProperty("spring.neo4j.authentication.password", "a-real-graph-password");
    }

    @Test
    void springBootDiscoversTheGuard() {
        // Spring Boot's own post-processors take constructor arguments this loader is not given; skipping
        // the ones it cannot build leaves exactly what the test is about, ours.
        assertTrue(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(EnvironmentPostProcessor.class, (factoryType, implementation, failure) -> { })
                .stream().anyMatch(RequiredSecretsEnvironmentPostProcessor.class::isInstance));
    }

    @Test
    void productionStartsOnlyWithEverySecretSet() {
        assertDoesNotThrow(() -> guard.postProcessEnvironment(production(), application));

        MockEnvironment noSigningKey = production().withProperty("jwt.secret", "");
        assertTrue(assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(noSigningKey, application))
                .getMessage().contains("jwt.secret"));

        MockEnvironment developmentPassword = production().withProperty("spring.datasource.password", "arthadhruva_app");
        assertTrue(assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(developmentPassword, application))
                .getMessage().contains("spring.datasource.password"));
    }

    @Test
    void outsideProductionTheDefaultsAreAllowed() {
        assertDoesNotThrow(() -> guard.postProcessEnvironment(new MockEnvironment(), application));
    }
}
