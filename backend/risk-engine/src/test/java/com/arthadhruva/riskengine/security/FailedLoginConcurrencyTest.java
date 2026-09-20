package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the failed-login counter is atomic: N truly parallel bad attempts must register as
 * exactly N. With the previous load-increment-save sequence, threads read the same value and
 * overwrite each other, so the final count came out below N (a lost update).
 */
@SpringBootTest
class FailedLoginConcurrencyTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired OrganizationService organizationService;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void parallelFailedLoginsAreNeverLost() throws Exception {
        int attempts = 50;
        Organization org = organizationService.findOrCreate("concurrency-test", "Concurrency Test");
        TenantContext.set(org.getId());
        try {
            userRepository.save(new User(org, "victim", passwordEncoder.encode("irrelevant-Password1"), Role.ANALYST));

            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch startGun = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    TenantContext.set(org.getId());
                    try {
                        startGun.await();
                        userRepository.recordFailedLogin(org.getId(), "victim", 1_000, Instant.now().plusSeconds(60));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            startGun.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            User victim = userRepository.findByOrganizationIdAndUsername(org.getId(), "victim").orElseThrow();
            assertEquals(attempts, victim.getFailedLoginAttempts());
            assertTrue(victim.getLockedUntil() == null, "threshold not reached, so no lock");
        } finally {
            TenantContext.clear();
        }
    }
}
