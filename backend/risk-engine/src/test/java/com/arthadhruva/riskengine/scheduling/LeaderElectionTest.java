package com.arthadhruva.riskengine.scheduling;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease against the real database. The electors here campaign only when told to and contend for a role
 * of their own, apart from the one the running application holds, so each step is deterministic.
 */
class LeaderElectionTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private final String role = "test-" + UUID.randomUUID();

    private LeaderElection elector(Duration lease) {
        return new LeaderElection(jdbc, role, UUID.randomUUID().toString(), lease, 0);
    }

    private long term() {
        return jdbc.queryForObject("SELECT fencing_token FROM leader_lease WHERE name = ?", Long.class, role);
    }

    @Test
    void oneReplicaLeadsUntilItResignsAndEachChangeOfLeaderIsANewTerm() {
        LeaderElection first = elector(Duration.ofSeconds(30));
        LeaderElection second = elector(Duration.ofSeconds(30));
        try {
            first.campaign();
            second.campaign();
            assertTrue(first.isLeader());
            assertFalse(second.isLeader(), "an unexpired lease is not taken");
            long term = first.fencingToken();
            assertEquals(term, term());

            first.campaign();
            assertEquals(term, first.fencingToken(), "renewing is not a new term");

            first.resign();
            assertFalse(first.isLeader());
            second.campaign();
            assertTrue(second.isLeader(), "a resigned lease is free at once");
            assertEquals(term + 1, second.fencingToken());

            first.campaign();
            assertFalse(first.isLeader(), "the old leader does not take it back while the new one holds it");
        } finally {
            first.resign();
            second.resign();
        }
    }

    @Test
    void aLeaderThatStopsRenewingIsReplacedOnceItsLeaseHasRunOut() throws Exception {
        LeaderElection stalled = elector(Duration.ofSeconds(1));
        LeaderElection standby = elector(Duration.ofSeconds(30));
        try {
            stalled.campaign();
            assertTrue(stalled.isLeader());
            standby.campaign();
            assertFalse(standby.isLeader());

            Thread.sleep(1_200);
            assertFalse(stalled.isLeader(), "its own clock ends its term, without being told");
            standby.campaign();
            assertTrue(standby.isLeader());
        } finally {
            stalled.resign();
            standby.resign();
        }
    }
}
