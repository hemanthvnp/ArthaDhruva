package com.arthadhruva.riskengine;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need the real Postgres, Redis and Neo4j. The containers are started once per JVM
 * (see the static block) and torn down by the Testcontainers reaper, so CI needs nothing provisioned
 * beyond a Docker daemon; every subclass shares one Spring context.
 *
 * <p>The application connects exactly as it does in production: as {@code arthadhruva_app}, the
 * non-owner role that row-level security and the append-only grants apply to, while Flyway migrates as
 * the owner. Connecting as the owner (as these tests once did) bypasses row-level security entirely and
 * would let a tenant-isolation bug pass every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("arthadhruva")
            .withUsername("arthadhruva")
            .withPassword("arthadhruva");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(DockerImageName.parse("neo4j:5-community"))
            .withAdminPassword("arthadhruva-test");

    // Singleton-container pattern: started once per JVM. With @Container on static fields, Testcontainers
    // stops them after each test class while Spring keeps the cached context (pointing at dead ports).
    static {
        POSTGRES.start();
        REDIS.start();
        NEO4J.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // The app role is created by migration V11 with this password (flyway placeholder appDbPassword).
        registry.add("spring.datasource.username", () -> "arthadhruva_app");
        registry.add("spring.datasource.password", () -> "arthadhruva_app");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.neo4j.uri", NEO4J::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", NEO4J::getAdminPassword);
    }
}
