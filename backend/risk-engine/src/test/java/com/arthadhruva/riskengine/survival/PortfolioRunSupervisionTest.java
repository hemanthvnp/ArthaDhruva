package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supervised runs against the real database: a waiting run is taken from the queue by a replica with a free
 * worker, a deferred one waits, a run whose worker stopped reporting goes back to the queue under a new
 * attempt and resumes from its checkpoints, one that has used its attempts is failed, and a replica that
 * shuts down hands its runs back. The scheduled passes are slowed to a standstill for tests
 * (AbstractIntegrationTest), so each pass here is the only one.
 *
 * <p>The passes look across organizations, as they do in production, so a pass made here may also pick up a
 * run another test left behind. Every assertion is therefore about this test's own run, never about how
 * many runs a pass touched.
 */
class PortfolioRunSupervisionTest extends AbstractIntegrationTest {

    private static final List<Scenario> SCENARIOS = List.of(Scenario.BASELINE);

    @Autowired PortfolioRiskService service;
    @Autowired PortfolioRiskEngine engine;
    @Autowired LoanCatalogService catalog;
    @Autowired OrganizationService organizations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    private Organization organization(String name) {
        return organizations.create(name + "-" + UUID.randomUUID().toString().substring(0, 8), name, false, null, null);
    }

    private List<LoanFeatures> loans() {
        return catalog.demoLoans().subList(0, 5);
    }

    /** Three chunks' worth, so a run can be part-way through. */
    private List<LoanFeatures> twentyLoans() {
        return catalog.demoLoans().subList(0, 20);
    }

    private <T> T inTenant(Organization org, Supplier<T> work) {
        TenantContext.set(org.getId());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private PortfolioRiskService.Run read(Organization org, UUID id) {
        return inTenant(org, () -> service.find(org.getId(), id).orElseThrow());
    }

    private String statusInTable(Organization org, UUID id) {
        return inTenant(org, () -> jdbc.queryForObject("SELECT status FROM portfolio_risk_run WHERE id = ?", String.class, id));
    }

    private PortfolioRiskService.Run finished(Organization org, UUID id) throws InterruptedException {
        PortfolioRiskService.Run run = read(org, id);
        for (int i = 0; i < 240 && run.active(); i++) {
            Thread.sleep(250);
            run = read(org, id);
        }
        return run;
    }

    /**
     * Looks at the queue, as every replica does, until this run has been taken. Another class's run may still
     * be finishing on the shared workers, and a pass takes only as many runs as there are free ones.
     */
    private boolean taken(UUID id, Supplier<List<LoanFeatures>> portfolio, boolean mayStartDeferred) throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            if (service.claimQueued(portfolio, mayStartDeferred).contains(id)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** A recorded run that no worker has, made to look as if its worker died {@code attempt} attempts in. */
    private UUID abandonedRun(Organization org, int attempt, List<LoanFeatures> loans) {
        UUID id = inTenant(org, () -> service.start(org.getId(), "sam", loans, SCENARIOS, null, true)).id();
        inTenant(org, () -> jdbc.update("UPDATE portfolio_risk_run SET status = 'RUNNING', deferred = FALSE, attempt = ?, "
                + "heartbeat_at = now() - interval '10 minutes' WHERE id = ?", attempt, id));
        return id;
    }

    @Test
    void aWaitingRunIsTakenFromTheQueueByAReplicaWithAFreeWorker() throws Exception {
        Organization org = organization("queue");
        // Recorded while its own replica had no worker for it: an ordinary run, left QUEUED.
        UUID id = inTenant(org, () -> service.start(org.getId(), "sam", loans(), SCENARIOS, null, true)).id();
        inTenant(org, () -> jdbc.update("UPDATE portfolio_risk_run SET deferred = FALSE, heartbeat_at = now() - interval '10 minutes' "
                + "WHERE id = ?", id));
        assertEquals(PortfolioRiskService.Status.QUEUED, read(org, id).status(), "waiting for a worker is not the same as stale");
        service.requeueStale();
        assertEquals(1, read(org, id).attempt(), "and the supervisor does not count it as a failed attempt");

        assertTrue(taken(id, this::loans, false));
        assertFalse(service.claimQueued(this::loans, true).contains(id), "a run is taken once");
        PortfolioRiskService.Run done = finished(org, id);
        assertEquals(PortfolioRiskService.Status.COMPLETED, done.status());
        assertEquals(1, done.attempt());
        assertEquals(5, done.loansDone());
        assertEquals(1, inTenant(org, () -> service.latestSnapshots(org.getId())).size());
    }

    @Test
    void aDeferredRunWaitsUntilTheEngineMayTakeIt() throws Exception {
        Organization org = organization("defer");
        PortfolioRiskService.Run queued = inTenant(org, () -> service.start(org.getId(), "sam", loans(), SCENARIOS, null, true));
        assertEquals(PortfolioRiskService.Status.QUEUED, queued.status());
        assertTrue(queued.deferred());

        // Waiting is not the same as stale: however long it waits, it is neither failed nor restarted.
        inTenant(org, () -> jdbc.update("UPDATE portfolio_risk_run SET heartbeat_at = now() - interval '10 minutes' WHERE id = ?", queued.id()));
        assertEquals(PortfolioRiskService.Status.QUEUED, read(org, queued.id()).status());
        service.requeueStale();
        assertFalse(service.claimQueued(this::loans, false).contains(queued.id()), "outside the off-peak window nothing starts");
        assertTrue(read(org, queued.id()).deferred());
        assertEquals(1, read(org, queued.id()).attempt());

        assertTrue(taken(queued.id(), this::loans, true));
        PortfolioRiskService.Run done = finished(org, queued.id());
        assertEquals(PortfolioRiskService.Status.COMPLETED, done.status());
        assertFalse(done.deferred());
        assertEquals(1, done.attempt());
        assertEquals(1, inTenant(org, () -> service.latestSnapshots(org.getId())).size());
    }

    @Test
    void aRunWhoseWorkerStoppedReportingIsRestartedUnderANewAttempt() throws Exception {
        Organization org = organization("restart");
        UUID id = abandonedRun(org, 1, loans());
        assertEquals(PortfolioRiskService.Status.RUNNING, read(org, id).status(), "it will be restarted, so it is not reported as failed");

        assertTrue(service.requeueStale().restarted() >= 1);
        PortfolioRiskService.Run requeued = read(org, id);
        assertEquals(PortfolioRiskService.Status.QUEUED, requeued.status());
        assertEquals(2, requeued.attempt());

        assertTrue(taken(id, this::loans, false));
        PortfolioRiskService.Run done = finished(org, id);
        assertEquals(PortfolioRiskService.Status.COMPLETED, done.status());
        assertEquals(2, done.attempt());
        assertEquals(5, done.loansTotal());
        assertEquals(5, inTenant(org, () -> service.loans(org.getId(), null, null, PortfolioRiskService.LoanOrder.LOAN_ID, 50, 0)).total());
        service.requeueStale();
        assertEquals("COMPLETED", statusInTable(org, id), "a finished run is left alone");
    }

    /**
     * The worker of a run died with two of its three chunks done. The next attempt projects only the third,
     * and stores exactly what a run that was never interrupted stores.
     */
    @Test
    void aRestartedRunResumesFromItsCheckpointsWithTheSameFigures() throws Exception {
        Organization org = organization("resume");
        UUID clean = inTenant(org, () -> service.start(org.getId(), "sam", twentyLoans(), SCENARIOS, null)).id();
        assertEquals(PortfolioRiskService.Status.COMPLETED, finished(org, clean).status());
        String uninterrupted = stored(org);

        // What the dead worker left: the chunks it had finished. Their content does not depend on the seed.
        Map<String, byte[]> chunks = new HashMap<>();
        engine.run(twentyLoans(), SCENARIOS, 1L, done -> { }, new PortfolioRiskEngine.Checkpoints() {
            @Override
            public Map<String, byte[]> load() {
                return Map.of();
            }

            @Override
            public void save(String key, byte[] chunk) {
                chunks.put(key, chunk);
            }
        });
        assertEquals(3, chunks.size());
        UUID id = abandonedRun(org, 1, twentyLoans());
        String unfinished = chunks.keySet().iterator().next();
        inTenant(org, () -> {
            chunks.forEach((key, chunk) -> {
                if (!key.equals(unfinished)) {
                    jdbc.update("INSERT INTO portfolio_risk_run_chunk (run_id, chunk_key, tenant_id, payload) VALUES (?, ?, ?, ?)",
                            id, key, org.getId(), chunk);
                }
            });
            return null;
        });

        double resumedBefore = meters.counter("risk.portfolio.run.chunks.resumed").count();
        service.requeueStale();
        assertTrue(taken(id, this::twentyLoans, false));
        PortfolioRiskService.Run done = finished(org, id);
        assertEquals(PortfolioRiskService.Status.COMPLETED, done.status());
        assertEquals(2, done.attempt());
        assertEquals(20, done.loansDone());
        assertTrue(meters.counter("risk.portfolio.run.chunks.resumed").count() - resumedBefore >= 2, "the two finished chunks were found");
        assertEquals(uninterrupted, stored(org), "the resumed run stored what an uninterrupted one does");
        assertEquals(0, inTenant(org, () -> jdbc.queryForObject("SELECT count(*) FROM portfolio_risk_run_chunk WHERE run_id = ?",
                Integer.class, id)), "a finished run keeps no checkpoints");
    }

    /**
     * Everything a run stored that does not say when or how long it took: the snapshot's figures and detail,
     * and every loan.
     */
    private String stored(Organization org) {
        return inTenant(org, () -> {
            StringBuilder all = new StringBuilder();
            for (PortfolioRiskService.Snapshot s : service.latestSnapshots(org.getId())) {
                all.append(s.scenario()).append('|').append(s.loans()).append('|').append(s.eclLifetime()).append('|')
                        .append(s.eclIfrs9()).append('|').append(s.pd12m()).append('|').append(s.detail()).append('\n');
            }
            service.forEachLoan(org.getId(), loan -> all.append(loan).append('\n'));
            return all.toString().replaceAll("\"elapsedMillis\":\\d+", "");
        });
    }

    @Test
    void aRunThatHasUsedItsAttemptsIsFailedAndStopsBlocking() {
        Organization org = organization("spent");
        UUID id = abandonedRun(org, PortfolioRiskService.MAX_ATTEMPTS, loans());
        assertEquals(PortfolioRiskService.Status.FAILED, read(org, id).status(), "reads as failed before the supervisor gets to it");

        assertTrue(service.requeueStale().abandoned() >= 1);
        assertEquals("FAILED", statusInTable(org, id));
        // The organization can start another run: the failed one no longer holds the one active place.
        PortfolioRiskService.Run next = inTenant(org, () -> service.start(org.getId(), "sam", loans(), SCENARIOS, null, true));
        assertEquals(PortfolioRiskService.Status.QUEUED, next.status());
    }

    @Test
    void aWorkerOfAnEarlierAttemptStoresNothing() throws Exception {
        Organization org = organization("fenced");
        PortfolioRiskService.Run run = inTenant(org, () -> service.start(org.getId(), "sam", loans(), SCENARIOS, null));
        // The run is taken over (as a supervisor would) before or while its first worker is at it.
        int moved = inTenant(org, () -> jdbc.update("UPDATE portfolio_risk_run SET attempt = attempt + 1, status = 'QUEUED' "
                + "WHERE id = ? AND status IN ('QUEUED', 'RUNNING')", run.id()));
        if (moved == 1) {
            Thread.sleep(3_000);   // far longer than five loans take
            PortfolioRiskService.Run after = read(org, run.id());
            assertEquals(PortfolioRiskService.Status.QUEUED, after.status(), "the first worker could not complete attempt 2's run");
            assertTrue(inTenant(org, () -> service.latestSnapshots(org.getId())).isEmpty(), "and stored none of its results");
        } else {
            // The worker finished before the takeover could be made: nothing to assert about fencing this time.
            assertEquals(PortfolioRiskService.Status.COMPLETED, read(org, run.id()).status());
        }
    }

    /** A deploy: the replica hands its run back at once, and whoever takes it carries on from the checkpoints. */
    @Test
    void aReplicaShuttingDownHandsItsRunBackToTheQueue() throws Exception {
        Organization org = organization("handoff");
        List<LoanFeatures> portfolio = catalog.demoLoans().subList(0, 60);
        UUID id = inTenant(org, () -> service.start(org.getId(), "sam", portfolio, SCENARIOS, null)).id();
        service.release();
        PortfolioRiskService.Run released = read(org, id);
        if (released.status() == PortfolioRiskService.Status.COMPLETED) {
            return;   // the run finished before the shutdown began: nothing was there to hand back
        }
        assertEquals(PortfolioRiskService.Status.QUEUED, released.status(), "back in the queue, not left to go stale");
        assertEquals(2, released.attempt());

        assertTrue(taken(id, () -> portfolio, false));
        PortfolioRiskService.Run done = finished(org, id);
        assertEquals(PortfolioRiskService.Status.COMPLETED, done.status());
        assertEquals(2, done.attempt());
        assertEquals(60, done.loansDone());
        assertTrue(inTenant(org, () -> service.latestSnapshots(org.getId())).size() == 1);
    }
}
