package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.cvar.CvarResult;
import com.arthadhruva.riskengine.roughset.DecisionOptions;
import com.arthadhruva.riskengine.roughset.PortfolioDecisionEngine;
import com.arthadhruva.riskengine.roughset.Region;
import com.arthadhruva.riskengine.scheduling.WorkerDb;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs portfolio risk projections in the background and keeps their results.
 *
 * <p>A projection of a few thousand loans takes longer than a request should, so a run is a row in
 * {@code portfolio_risk_run}: the request that starts it returns at once, the client polls the row, and a
 * worker moves it QUEUED, RUNNING, then COMPLETED or FAILED while reporting progress. The row lives in
 * the database, not in this JVM, so any replica answers the poll.
 *
 * <p><b>The queue is the table.</b> A QUEUED run belongs to no replica. The replica that recorded it takes it
 * at once if it has a free worker; otherwise it waits, and every replica looks for waiting runs whenever it
 * has a worker free ({@link #claimQueued}, {@code FOR UPDATE SKIP LOCKED}, so two replicas never take the
 * same one). Nothing waits in a JVM's memory, so a replica that stops takes no queued run down with it, and
 * an idle replica is never left out while another is full.
 *
 * <p><b>One active run per organization</b> is enforced by a partial unique index, not by a check
 * here: two concurrent requests cannot both insert an active row, on any number of replicas.
 *
 * <p><b>A worker that dies</b> (crash) leaves its row RUNNING. A worker refreshes {@code heartbeat_at} on a
 * timer for as long as it is alive, whether or not it is getting anywhere: the heartbeat says "this worker
 * exists", the progress count says how far it is, and a run that is only waiting its turn on a busy compute
 * pool is not mistaken for a dead one. A RUNNING run whose heartbeat is older than {@link #STALE_AFTER} is
 * stale; {@link #requeueStale}, driven by the leading replica, puts it back in the queue under the next
 * attempt number, up to {@link #MAX_ATTEMPTS}, after which it is failed and no longer blocks a new one.
 * Each attempt has a number and a worker writes only under its own, so a worker that was slow, not dead,
 * cannot overwrite the attempt that replaced it; it is told to stop the next time its heartbeat finds the
 * run is no longer its own.
 *
 * <p><b>A worker that is shut down</b> (deploy) hands its runs back to the queue as it goes ({@link #release}),
 * so another replica continues them in seconds instead of after the stale timeout.
 *
 * <p><b>A later attempt resumes.</b> The chunks an attempt has finished are kept with the run
 * ({@code portfolio_risk_run_chunk}), and the next attempt projects only the rest (see
 * {@link PortfolioRiskEngine.Checkpoints}); the figures are those of an uninterrupted run.
 *
 * <p><b>A deferred run</b> is recorded and left QUEUED until a replica has nothing else to do (and, when
 * an off-peak window is configured, until that window): batch work is moved to when it does not compete
 * with interactive requests.
 *
 * <p>A finished run stores one {@code portfolio_risk_snapshot} per scenario for its date (replacing an
 * earlier run of the same day) and the baseline's loan-by-loan results, in the same transaction that
 * marks the run completed: the results and the "done" a client is polling for appear together or not at
 * all.
 */
@Service
public class PortfolioRiskService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskService.class);

    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    static final int MAX_ATTEMPTS = 3;
    private static final long PROGRESS_EVERY_MILLIS = 2_000;
    /** Well inside {@link #STALE_AFTER}: several beats may be lost to a slow database before a run looks dead. */
    private static final long HEARTBEAT_EVERY_MILLIS = 15_000;
    private static final String STALE_MESSAGE = "The server stopped before the run finished; start it again.";

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    /**
     * @param attempt  1 for a run that has not been restarted
     * @param deferred still waiting for the engine to be idle
     */
    public record Run(UUID id, Status status, List<String> scenarios, int loansTotal, int loansDone, String requestedBy,
                      Instant requestedAt, Instant startedAt, Instant finishedAt, String error, int attempt, boolean deferred) {
        public boolean active() {
            return status == Status.QUEUED || status == Status.RUNNING;
        }
    }

    /**
     * One scenario's stored result: the headline numbers, and (when read with it) the full
     * {@link PortfolioRiskEngine.ScenarioRisk} as {@code detail}.
     */
    public record Snapshot(LocalDate asOf, String scenario, int loans, int loansExcluded, int projectedLoans, double exposure,
                           double pd12m, double pdLifetime, double ecl12m, double eclLifetime, double eclIfrs9,
                           int stage1, int stage2, int stage3, String modelVersion, Instant computedAt, JsonNode detail,
                           @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode decision) {
    }

    /** What a run needs to be run again, stored with it: the loans are read from the portfolio. */
    record StoredRequest(List<String> scenarios, String decisionScenario, DecisionOptions.Resolved decision) {
    }

    /** What one pass over the stale runs did: how many went back to the queue, how many were given up. */
    public record Supervision(int restarted, int abandoned) {
        static final Supervision NOTHING = new Supervision(0, 0);
    }

    /** A run taken from the queue, as the worker role sees it. */
    private record Claimed(UUID id, Long tenantId, int attempt, String request) {
    }

    /**
     * A run this replica is working on. The thread doing it can be told to stop (another attempt has the run,
     * or this replica is shutting down); once {@link #detach} has returned, no interrupt can reach that thread
     * any more, so one meant for this run never lands on the pool thread's next task.
     */
    private static final class Active {
        final Long tenantId;
        final UUID id;
        final int attempt;
        private Thread runner;
        private boolean superseded;

        Active(Long tenantId, UUID id, int attempt) {
            this.tenantId = tenantId;
            this.id = id;
            this.attempt = attempt;
        }

        synchronized void attach(Thread thread) {
            runner = thread;
            if (superseded) {
                thread.interrupt();
            }
        }

        synchronized void supersede() {
            superseded = true;
            if (runner != null) {
                runner.interrupt();
            }
        }

        /** @return whether the run was taken from this worker */
        synchronized boolean detach() {
            runner = null;
            return superseded;
        }
    }

    /** Another attempt has taken this run over; this one's results are not stored. */
    private static class SupersededException extends RuntimeException {
        SupersededException() {
            super("The run was taken over by another attempt");
        }
    }

    /** A run's request for regions: the scenario the loans are judged under, and how. */
    public record DecisionPlan(Scenario target, DecisionOptions.Resolved options) {
    }

    /**
     * Where one loan fell in the three-way decision of the latest run that asked for one.
     *
     * @param high number of variants at or above the threshold, out of {@code members}
     */
    public record RegionInfo(Region region, int high, int members, double metricMin, double metricMax, double eclMin, double eclMax,
                             boolean extrapolated) {
    }

    /** The organization already has a run in progress. */
    public static class RunInProgressException extends RuntimeException {
        private final transient Run run;

        RunInProgressException(Run run) {
            super("A portfolio run is already in progress");
            this.run = run;
        }

        public Run run() {
            return run;
        }
    }

    /** No worker is free on this replica and as many runs as may wait are waiting already. */
    public static class BusyException extends RuntimeException {
        BusyException() {
            super("The risk engine is busy; try again shortly");
        }
    }

    /**
     * A page of the latest run's loan-by-loan results.
     *
     * @param asOf  the date of the run the loans belong to; null when there has been none
     * @param total loans matching the filter, across all pages
     * @param regions the region of each listed loan, by loan id; empty when the latest run did not ask for regions
     */
    public record LoanPage(LocalDate asOf, int total, List<PortfolioRiskEngine.LoanResult> loans, Map<String, RegionInfo> regions) {
    }

    /**
     * The one-year loss distribution of the portfolio as of its latest run.
     *
     * @param loans   loans simulated
     * @param sampled the loans are a sample standing for a larger portfolio
     */
    public record LossDistribution(LocalDate asOf, int loans, boolean sampled, CvarResult result) {
    }

    /** How the loan listing may be ordered: a fixed list, because the choice becomes part of the query. */
    public enum LoanOrder {
        ECL_LIFETIME("ecl_lifetime DESC"), ECL_IFRS9("ecl_ifrs9 DESC"), PD_12M("pd_12m DESC"), EXPOSURE("exposure DESC"),
        LOAN_ID("loan_id");

        private final String sql;

        LoanOrder(String sql) {
            this.sql = sql;
        }
    }

    /** Namespace of the advisory lock that serializes storing results per organization ("ADPR"). */
    private static final long STORE_LOCK = 0x41445052L << 32;

    private final JdbcTemplate jdbc;
    /** Sees every organization's runs, and only runs: for the queue and the stale sweep (see {@link WorkerDb}). */
    private final JdbcTemplate workerJdbc;
    private final TransactionTemplate transactions;
    private final PortfolioRiskEngine engine;
    private final PortfolioDecisionEngine decisionEngine;
    private final CvarEngine lossEngine;
    private final ExecutorService runs;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ObjectMapper mapper = MAPPER;
    private final Timer runTimer;
    private final Counter restarts;
    private final Counter resumedChunks;
    private final int concurrent;
    private final int maxWaiting;
    /** One permit per run this replica may work on at once; held from the claim to the end of the run. */
    private final Semaphore slots;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats;

    public PortfolioRiskService(JdbcTemplate jdbc, WorkerDb workerDb, PlatformTransactionManager transactionManager,
                                PortfolioRiskEngine engine, PortfolioDecisionEngine decisionEngine, CvarEngine lossEngine,
                                @Qualifier("riskRunExecutor") ExecutorService runs,
                                @Value("${risk.runs.concurrent:2}") int concurrent, @Value("${risk.runs.queue:8}") int maxWaiting,
                                MeterRegistry meters) {
        this.jdbc = jdbc;
        this.workerJdbc = workerDb.jdbc();
        this.transactions = new TransactionTemplate(transactionManager);
        this.engine = engine;
        this.decisionEngine = decisionEngine;
        this.lossEngine = lossEngine;
        this.runs = runs;
        this.concurrent = concurrent;
        this.maxWaiting = maxWaiting;
        this.slots = new Semaphore(concurrent);
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "risk-run-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.runTimer = Timer.builder("risk.portfolio.run").description("Portfolio risk runs, start to stored result")
                .register(meters);
        this.restarts = Counter.builder("risk.portfolio.run.restarts").description("Stale portfolio runs put back in the queue")
                .register(meters);
        this.resumedChunks = Counter.builder("risk.portfolio.run.chunks.resumed")
                .description("Checkpointed chunks that restarted portfolio runs loaded instead of starting from nothing").register(meters);
        Gauge.builder("risk.portfolio.run.active", active, Map::size)
                .description("Portfolio risk runs this replica is working on").register(meters);
    }

    /**
     * Records a run over {@code loans} and returns its row. The tenant context of the caller must be set.
     *
     * @throws RunInProgressException if the organization already has an active run
     * @throws BusyException          if no worker is free here and the queue is full
     */
    public Run start(Long tenantId, String requestedBy, List<LoanFeatures> loans, List<Scenario> scenarios) {
        return start(tenantId, requestedBy, loans, scenarios, null);
    }

    /**
     * As above, and when {@code decision} is given the run also judges every loan under the plan's target scenario
     * (see {@link PortfolioDecisionEngine}) and stores each loan's region with the results.
     */
    public Run start(Long tenantId, String requestedBy, List<LoanFeatures> loans, List<Scenario> scenarios, DecisionPlan decision) {
        return start(tenantId, requestedBy, loans, scenarios, decision, false);
    }

    /**
     * As above; a {@code deferred} run is only recorded here, and taken by {@link #claimQueued} once a replica
     * is idle, so it never takes a worker from an interactive run and is never refused as busy.
     */
    public Run start(Long tenantId, String requestedBy, List<LoanFeatures> loans, List<Scenario> scenarios, DecisionPlan decision,
                     boolean deferred) {
        failStaleRuns(tenantId);
        UUID id = UUID.randomUUID();
        List<String> scenarioNames = scenarios.stream().map(Scenario::name).toList();
        String request = mapper.writeValueAsString(new StoredRequest(scenarioNames,
                decision == null ? null : decision.target().name(), decision == null ? null : decision.options()));
        try {
            jdbc.update("INSERT INTO portfolio_risk_run (id, tenant_id, requested_by, status, scenarios, loans_total, deferred, request) "
                            + "VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?::jsonb)",
                    id, tenantId, requestedBy, String.join(",", scenarioNames), loans.size(), deferred, request);
        } catch (DuplicateKeyException e) {
            throw new RunInProgressException(latest(tenantId).orElseThrow());
        }
        // No other run of the organization is active, so whatever checkpoints are left belong to none.
        jdbc.update("DELETE FROM portfolio_risk_run_chunk WHERE tenant_id = ? AND run_id <> ?", tenantId, id);
        if (!deferred && !takeNow(tenantId, id, loans, scenarios, decision) && waiting() > maxWaiting
                // Compensation: the row recorded a run nobody will get to soon, so it is closed, not left
                // waiting. Matching nothing means another replica took it in the meantime: then it is running.
                && jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() "
                        + "WHERE id = ? AND tenant_id = ? AND status = 'QUEUED'", "The risk engine was busy", id, tenantId) == 1) {
            throw new BusyException();
        }
        return find(tenantId, id).orElseThrow();
    }

    /**
     * Starts a run just recorded on one of this replica's own workers, if one is free, with the loans already
     * in hand: the usual case, and it costs the request no wait for the next look at the queue.
     */
    private boolean takeNow(Long tenantId, UUID id, List<LoanFeatures> loans, List<Scenario> scenarios, DecisionPlan decision) {
        if (!slots.tryAcquire()) {
            return false;
        }
        boolean handed = false;
        try {
            handed = jdbc.update("UPDATE portfolio_risk_run SET status = 'RUNNING', started_at = now(), heartbeat_at = now() "
                    + "WHERE id = ? AND tenant_id = ? AND attempt = 1 AND status = 'QUEUED'", id, tenantId) == 1
                    && launch(new Active(tenantId, id, 1), loans, scenarios, decision);
            return handed;
        } finally {
            if (!handed) {
                slots.release();
            }
        }
    }

    /** Runs waiting for a worker, across organizations (deferred ones aside). Unknown counts as none. */
    private int waiting() {
        try {
            Integer count = workerJdbc.queryForObject("SELECT count(*) FROM portfolio_risk_run WHERE status = 'QUEUED' AND NOT deferred",
                    Integer.class);
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            log.warn("Could not count the waiting portfolio runs: {}", e.toString());
            return 0;
        }
    }

    /**
     * Hands a run this replica has claimed (its row is RUNNING, a slot is held) to a worker, which gives the
     * slot back when it is done. The tenant context of the caller must be set.
     *
     * @return false when no worker would take it, which happens only while shutting down: the run is back in
     *         the queue and the slot is still the caller's
     */
    private boolean launch(Active run, List<LoanFeatures> loans, List<Scenario> scenarios, DecisionPlan decision) {
        active.put(run.id, run);
        try {
            runs.execute(() -> {
                try {
                    execute(run, loans, scenarios, decision);
                } finally {
                    active.remove(run.id);
                    slots.release();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            active.remove(run.id);
            jdbc.update("UPDATE portfolio_risk_run SET status = 'QUEUED' WHERE id = ? AND tenant_id = ? AND attempt = ? AND status = 'RUNNING'",
                    run.id, run.tenantId, run.attempt);
            return false;
        }
    }

    /**
     * Takes waiting runs from the queue for as long as this replica has a free worker; called by every
     * replica, often. Each claim is one statement that locks the row it takes and skips rows another
     * replica is taking, so a run goes to exactly one worker. Oldest first; a deferred run only when
     * {@code mayStartDeferred} and this replica is doing nothing else.
     *
     * @param portfolio the loans of the organization whose tenant context is set when it is called
     * @return the runs taken
     */
    public List<UUID> claimQueued(Supplier<List<LoanFeatures>> portfolio, boolean mayStartDeferred) {
        List<UUID> taken = new ArrayList<>();
        while (slots.tryAcquire()) {
            boolean handed = false;
            try {
                boolean idle = slots.availablePermits() == concurrent - 1;
                List<Claimed> claimed = workerJdbc.query("UPDATE portfolio_risk_run r SET status = 'RUNNING', deferred = FALSE, "
                                + "started_at = now(), heartbeat_at = now() "
                                + "WHERE r.id = (SELECT id FROM portfolio_risk_run WHERE status = 'QUEUED' AND request IS NOT NULL "
                                + "AND (NOT deferred OR ?) ORDER BY deferred, requested_at LIMIT 1 FOR UPDATE SKIP LOCKED) "
                                + "RETURNING r.id, r.tenant_id, r.attempt, r.request::text",
                        (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3), rs.getString(4)),
                        mayStartDeferred && idle);
                if (claimed.isEmpty()) {
                    return taken;
                }
                handed = dispatch(claimed.get(0), portfolio);
                if (handed) {
                    taken.add(claimed.get(0).id());
                }
            } finally {
                if (!handed) {
                    slots.release();
                }
            }
        }
        return taken;
    }

    /**
     * Rebuilds a claimed run from its stored request and hands it to a worker. False when it was not handed
     * over: it could not be rebuilt (and is failed), or no worker would take it (and is back in the queue).
     */
    private boolean dispatch(Claimed claimed, Supplier<List<LoanFeatures>> portfolio) {
        Long callerTenant = TenantContext.getOptional().orElse(null);
        TenantContext.set(claimed.tenantId());
        try {
            List<LoanFeatures> loans;
            List<Scenario> scenarios;
            DecisionPlan plan;
            try {
                StoredRequest request = mapper.readValue(claimed.request(), StoredRequest.class);
                scenarios = request.scenarios().stream().map(Scenario::named).toList();
                plan = request.decision() == null ? null : new DecisionPlan(Scenario.named(request.decisionScenario()), request.decision());
                loans = portfolio.get();
                if (loans.isEmpty()) {
                    throw new IllegalStateException("The portfolio has no loans");
                }
            } catch (RuntimeException e) {
                log.error("Portfolio risk run {} of tenant {} cannot be started from its stored request", claimed.id(), claimed.tenantId(), e);
                jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() "
                                + "WHERE id = ? AND tenant_id = ? AND attempt = ?",
                        "The run could not be started; the error has been logged.", claimed.id(), claimed.tenantId(), claimed.attempt());
                return false;
            }
            jdbc.update("UPDATE portfolio_risk_run SET loans_total = ? WHERE id = ? AND tenant_id = ? AND attempt = ?",
                    loans.size(), claimed.id(), claimed.tenantId(), claimed.attempt());
            return launch(new Active(claimed.tenantId(), claimed.id(), claimed.attempt()), loans, scenarios, plan);
        } finally {
            if (callerTenant != null) {
                TenantContext.set(callerTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * One pass over every organization's stale runs, for the leading replica: a RUNNING run whose worker
     * has not reported for {@link #STALE_AFTER} goes back to the queue under the next attempt number, or is
     * failed when it has used its attempts or has nothing to be restarted from. Two statements however many
     * organizations there are, each conditional on the run still being stale when it is written, so a worker
     * that was only slow and has since reported in, or another supervisor, makes them match nothing. The
     * age is judged by the database's clock, the one that wrote the heartbeats.
     */
    public Supervision requeueStale() {
        double staleSeconds = STALE_AFTER.toMillis() / 1000.0;
        int abandoned = workerJdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() "
                        + "WHERE status = 'RUNNING' AND heartbeat_at < now() - make_interval(secs => ?) AND (attempt >= ? OR request IS NULL)",
                STALE_MESSAGE, staleSeconds, MAX_ATTEMPTS);
        List<String> restarted = workerJdbc.query("UPDATE portfolio_risk_run SET attempt = attempt + 1, status = 'QUEUED', loans_done = 0, "
                        + "heartbeat_at = now() WHERE status = 'RUNNING' AND heartbeat_at < now() - make_interval(secs => ?) "
                        + "AND attempt < ? AND request IS NOT NULL RETURNING id || ' of tenant ' || tenant_id || ' as attempt ' || attempt",
                (rs, i) -> rs.getString(1), staleSeconds, MAX_ATTEMPTS);
        for (String run : restarted) {
            log.warn("Portfolio risk run {} stopped reporting; back in the queue", run);
        }
        restarts.increment(restarted.size());
        return restarted.isEmpty() && abandoned == 0 ? Supervision.NOTHING : new Supervision(restarted.size(), abandoned);
    }

    /**
     * On shutdown: every run this replica is working on goes back to the queue under the next attempt number
     * (so nothing this replica still writes can land) and its worker is told to stop. What it had finished is
     * in the checkpoints, so the replica that takes it over continues from there.
     */
    @PreDestroy
    void shutdown() {
        release();
        heartbeats.shutdownNow();
    }

    /** Hands every run this replica is working on back to the queue. */
    void release() {
        for (Active run : active.values()) {
            TenantContext.set(run.tenantId);
            try {
                if (jdbc.update("UPDATE portfolio_risk_run SET attempt = attempt + 1, status = 'QUEUED', heartbeat_at = now() "
                        + "WHERE id = ? AND tenant_id = ? AND attempt = ? AND status = 'RUNNING'", run.id, run.tenantId, run.attempt) == 1) {
                    log.info("Portfolio risk run {} handed back to the queue on shutdown", run.id);
                }
            } catch (RuntimeException e) {
                log.warn("Portfolio risk run {} could not be handed back; it will be restarted once it is stale: {}", run.id, e.toString());
            } finally {
                TenantContext.clear();
            }
            run.supersede();
        }
    }

    /** Whether the organization has a snapshot dated {@code from} or later. */
    public boolean hasSnapshotSince(Long tenantId, LocalDate from) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM portfolio_risk_snapshot WHERE tenant_id = ? AND as_of >= ?)",
                Boolean.class, tenantId, from));
    }

    /** The work of one attempt, on a run worker; the row is already RUNNING under {@code run.attempt}. */
    private void execute(Active run, List<LoanFeatures> loans, List<Scenario> scenarios, DecisionPlan decision) {
        Long tenantId = run.tenantId;
        UUID id = run.id;
        int attempt = run.attempt;
        TenantContext.set(tenantId);
        run.attach(Thread.currentThread());
        ScheduledFuture<?> heartbeat = null;
        Timer.Sample sample = Timer.start();
        try {
            heartbeat = heartbeats.scheduleWithFixedDelay(() -> beat(run), HEARTBEAT_EVERY_MILLIS, HEARTBEAT_EVERY_MILLIS,
                    TimeUnit.MILLISECONDS);
            LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
            AtomicLong lastProgress = new AtomicLong(System.currentTimeMillis());
            PortfolioRiskEngine.Checkpoints checkpoints = checkpoints(run);
            PortfolioRiskEngine.PortfolioRisk result = engine.run(loans, scenarios, seed(tenantId, asOf), done -> {
                long now = System.currentTimeMillis();
                if (now - lastProgress.get() >= PROGRESS_EVERY_MILLIS) {
                    lastProgress.set(now);
                    jdbc.update("UPDATE portfolio_risk_run SET loans_done = ?, heartbeat_at = now() "
                            + "WHERE id = ? AND tenant_id = ? AND attempt = ?", done, id, tenantId, attempt);
                }
            }, checkpoints);
            // The projection is finished and loans_done stays where it is; the timer heartbeat says the region analysis is alive.
            PortfolioDecisionEngine.Result regions = decision == null ? null
                    : decisionEngine.run(loans, decision.target(), decision.options(), done -> { }, checkpoints, engine.modelKey());
            transactions.executeWithoutResult(tx -> {
                // First, and under this attempt only: it locks the row until commit, so a restart cannot
                // slip in between this check and the results, and a superseded attempt stores nothing.
                if (jdbc.update("UPDATE portfolio_risk_run SET status = 'COMPLETED', loans_done = loans_total, heartbeat_at = now(), "
                        + "finished_at = now() WHERE id = ? AND tenant_id = ? AND attempt = ? AND status = 'RUNNING'",
                        id, tenantId, attempt) == 0) {
                    throw new SupersededException();
                }
                store(tenantId, asOf, result);
                if (regions != null) {
                    storeRegions(tenantId, asOf, regions);
                }
                jdbc.update("DELETE FROM portfolio_risk_run_chunk WHERE tenant_id = ? AND run_id = ?", tenantId, id);
            });
        } catch (SupersededException e) {
            log.info("Portfolio risk run {} attempt {} was superseded; its results were not stored", id, attempt);
        } catch (RuntimeException e) {
            boolean superseded = run.detach();
            Thread.interrupted();   // an interrupt meant for this run must not fail the statements below
            if (superseded) {
                log.info("Portfolio risk run {} attempt {} was taken from this worker; stopping", id, attempt);
            } else {
                log.error("Portfolio risk run {} failed for tenant {}", id, tenantId, e);
                try {
                    jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() "
                                    + "WHERE id = ? AND tenant_id = ? AND attempt = ?",
                            "The run failed; the error has been logged.", id, tenantId, attempt);
                    jdbc.update("DELETE FROM portfolio_risk_run_chunk WHERE tenant_id = ? AND run_id = ?", tenantId, id);
                } catch (RuntimeException secondary) {
                    log.error("Could not mark portfolio risk run {} as failed", id, secondary);
                }
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            run.detach();
            Thread.interrupted();   // nor may one that arrived late reach this pool thread's next run
            sample.stop(runTimer);
            TenantContext.clear();
        }
    }

    /**
     * Says the worker of {@code run} is alive, from the heartbeat thread. If the row is no longer RUNNING under
     * this attempt, the run has been taken over (or released), and the worker is told to stop.
     */
    private void beat(Active run) {
        TenantContext.set(run.tenantId);
        try {
            if (jdbc.update("UPDATE portfolio_risk_run SET heartbeat_at = now() WHERE id = ? AND tenant_id = ? AND attempt = ? "
                    + "AND status = 'RUNNING'", run.id, run.tenantId, run.attempt) == 0) {
                run.supersede();
            }
        } catch (RuntimeException e) {
            log.warn("Heartbeat of portfolio risk run {} could not be written: {}", run.id, e.toString());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The chunks earlier attempts of {@code run} finished, and where this one's go: the projection's and the
     * region analysis's alike, read from the table once. Storing one is a saving, not part of the result: if
     * it cannot be written the run carries on, and a later attempt projects that chunk again.
     */
    private PortfolioRiskEngine.Checkpoints checkpoints(Active run) {
        return new PortfolioRiskEngine.Checkpoints() {
            private Map<String, byte[]> stored;

            @Override
            public Map<String, byte[]> load() {
                if (stored != null) {
                    return stored;
                }
                stored = new HashMap<>();
                if (run.attempt > 1) {
                    jdbc.query("SELECT chunk_key, payload FROM portfolio_risk_run_chunk WHERE tenant_id = ? AND run_id = ?",
                            (RowCallbackHandler) rs -> stored.put(rs.getString(1), rs.getBytes(2)), run.tenantId, run.id);
                    resumedChunks.increment(stored.size());
                    log.info("Portfolio risk run {} attempt {} resumes with {} chunk(s) already projected", run.id, run.attempt, stored.size());
                }
                return stored;
            }

            @Override
            public void save(String key, byte[] chunk) {
                try {
                    jdbc.update("INSERT INTO portfolio_risk_run_chunk (run_id, chunk_key, tenant_id, payload) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT DO NOTHING", run.id, key, run.tenantId, chunk);
                } catch (RuntimeException e) {
                    log.warn("A checkpoint of portfolio risk run {} could not be stored: {}", run.id, e.toString());
                }
            }
        };
    }

    /**
     * Stores a run's results; must be called inside a transaction.
     *
     * <p>A date holds one consistent set: everything stored for it is replaced, including scenarios an
     * earlier run of the day had and this one does not, which would otherwise sit beside the new figures
     * describing a portfolio that may since have changed. Writers of one organization are serialized by an
     * advisory lock held to the end of the transaction, so a scheduled run and a requested one finishing
     * together replace each other cleanly instead of colliding on the unique key.
     */
    private void store(Long tenantId, LocalDate asOf, PortfolioRiskEngine.PortfolioRisk result) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (RowCallbackHandler) rs -> { }, STORE_LOCK | (tenantId & 0xFFFFFFFFL));
        jdbc.update("DELETE FROM portfolio_risk_snapshot WHERE tenant_id = ? AND as_of = ?", tenantId, asOf);
        for (PortfolioRiskEngine.ScenarioRisk s : result.scenarios()) {
            jdbc.update("INSERT INTO portfolio_risk_snapshot (tenant_id, as_of, scenario, loans, loans_excluded, sampled_loans, exposure, "
                            + "pd_12m, pd_lifetime, ecl_12m, ecl_lifetime, ecl_ifrs9, stage1, stage2, stage3, model_version, detail) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                    tenantId, asOf, s.scenario(), result.loans(), result.excluded(), result.projected(), s.exposure(), s.pd12m(),
                    s.pdLifetime(), s.ecl12m(), s.eclLifetime(), s.eclIfrs9(), s.stages().get(0).loans(), s.stages().get(1).loans(),
                    s.stages().get(2).loans(), result.modelVersion(), mapper.writeValueAsString(s));
        }
        if (result.baselineLoans().isEmpty()) {
            return;   // a run without the baseline says nothing about the allowance: keep the listing there is
        }
        jdbc.update("DELETE FROM portfolio_risk_loan WHERE tenant_id = ?", tenantId);
        jdbc.batchUpdate("INSERT INTO portfolio_risk_loan (tenant_id, loan_id, as_of, property_state, exposure, pd_12m, pd_lifetime, "
                        + "lgd, ecl_12m, ecl_lifetime, ecl_ifrs9, stage, stage_reason, weight) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                result.baselineLoans(), 1000, (ps, loan) -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, loan.loanId());
                    ps.setObject(3, asOf);
                    ps.setString(4, loan.state());
                    ps.setDouble(5, loan.exposure());
                    ps.setDouble(6, loan.pd12m());
                    ps.setDouble(7, loan.pdLifetime());
                    ps.setDouble(8, loan.lgd());
                    ps.setDouble(9, loan.ecl12m());
                    ps.setDouble(10, loan.eclLifetime());
                    ps.setDouble(11, loan.eclIfrs9());
                    ps.setInt(12, loan.stage());
                    ps.setString(13, loan.stageReason());
                    ps.setDouble(14, loan.weight());
                });
    }

    /**
     * Stores a run's regions; must be called inside the transaction that called {@link #store}, which has just
     * replaced the loan rows. The aggregates go on the target scenario's snapshot, each loan's region beside its
     * baseline result.
     */
    private void storeRegions(Long tenantId, LocalDate asOf, PortfolioDecisionEngine.Result regions) {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("scenario", regions.scenario());
        summary.set("parameters", mapper.valueToTree(regions.parameters()));
        summary.put("loans", regions.loans());
        summary.put("excluded", regions.excluded());
        summary.put("exposure", regions.exposure());
        summary.set("regions", mapper.valueToTree(regions.regions()));
        summary.put("eclLower", regions.eclLower());
        summary.put("eclUpper", regions.eclUpper());
        summary.put("eclHighBase", regions.eclHighBase());
        summary.set("boundaryByState", mapper.valueToTree(regions.boundaryByState()));
        summary.put("method", regions.method());
        jdbc.update("UPDATE portfolio_risk_snapshot SET decision = ?::jsonb WHERE tenant_id = ? AND as_of = ? AND scenario = ?",
                mapper.writeValueAsString(summary), tenantId, asOf, regions.scenario());
        jdbc.batchUpdate("UPDATE portfolio_risk_loan SET region = ?, region_high = ?, region_members = ?, region_metric_min = ?, "
                        + "region_metric_max = ?, region_ecl_min = ?, region_ecl_max = ?, region_extrapolated = ? "
                        + "WHERE tenant_id = ? AND loan_id = ?",
                regions.loanRegions(), 1000, (ps, l) -> {
                    ps.setString(1, l.region().name());
                    ps.setInt(2, l.high());
                    ps.setInt(3, l.members());
                    ps.setDouble(4, l.metricMin());
                    ps.setDouble(5, l.metricMax());
                    ps.setDouble(6, l.eclMin());
                    ps.setDouble(7, l.eclMax());
                    ps.setBoolean(8, l.extrapolated());
                    ps.setLong(9, tenantId);
                    ps.setString(10, l.loanId());
                });
    }

    /** The same sample for every run of an organization on a given day, a new one the next day. */
    private static long seed(Long tenantId, LocalDate asOf) {
        return tenantId * 1_000_003L + asOf.toEpochDay();
    }

    /**
     * A new request replaces a stale run outright, whether or not it could still have been restarted. Stale
     * is a RUNNING run whose worker stopped reporting; a QUEUED one is waiting for a worker and is nobody's
     * to report on, unless it has nothing to be started from, in which case no worker will ever take it.
     */
    private void failStaleRuns(Long tenantId) {
        jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() "
                        + "WHERE tenant_id = ? AND (status = 'RUNNING' OR (status = 'QUEUED' AND request IS NULL)) "
                        + "AND heartbeat_at < ?",
                STALE_MESSAGE, tenantId, Timestamp.from(Instant.now().minus(STALE_AFTER)));
    }

    public Optional<Run> find(Long tenantId, UUID id) {
        return jdbc.query(RUN_COLUMNS + " WHERE tenant_id = ? AND id = ?", RUN, tenantId, id).stream().findFirst();
    }

    /** The most recent run of the organization, whatever its state. */
    public Optional<Run> latest(Long tenantId) {
        return jdbc.query(RUN_COLUMNS + " WHERE tenant_id = ? ORDER BY requested_at DESC LIMIT 1", RUN, tenantId).stream().findFirst();
    }

    /**
     * The scenarios of the most recent date, with their detail. Only that date: an older snapshot of a
     * scenario the latest run did not include belongs to another day's portfolio and model, and showing it
     * beside the new ones would compare unlike with unlike.
     */
    public List<Snapshot> latestSnapshots(Long tenantId) {
        return jdbc.query("SELECT " + SNAPSHOT_COLUMNS + ", detail::text AS detail FROM portfolio_risk_snapshot "
                        + "WHERE tenant_id = ? AND as_of = (SELECT max(as_of) FROM portfolio_risk_snapshot WHERE tenant_id = ?) "
                        + "ORDER BY scenario",
                (rs, i) -> snapshot(rs, mapper.readTree(rs.getString("detail"))), tenantId, tenantId);
    }

    /** The headline numbers of one scenario over time, newest first (no per-month detail). */
    public List<Snapshot> history(Long tenantId, String scenario, int limit) {
        return jdbc.query("SELECT " + SNAPSHOT_COLUMNS + " FROM portfolio_risk_snapshot WHERE tenant_id = ? AND scenario = ? "
                + "ORDER BY as_of DESC LIMIT ?", (rs, i) -> snapshot(rs, null), tenantId, scenario, limit);
    }

    /**
     * The latest run's baseline results, loan by loan.
     *
     * @param region only loans in this region of the latest region analysis; null for all
     */
    public LoanPage loans(Long tenantId, Integer stage, Region region, LoanOrder order, int limit, int offset) {
        List<Object> where = new ArrayList<>();
        where.add(tenantId);
        String filter = "";
        if (stage != null) {
            filter = " AND stage = ?";
            where.add(stage);
        }
        if (region != null) {
            filter += " AND region = ?";
            where.add(region.name());
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM portfolio_risk_loan WHERE tenant_id = ?" + filter, Integer.class,
                where.toArray());
        List<Object> page = new ArrayList<>(where);
        page.add(limit);
        page.add(offset);
        List<PortfolioRiskEngine.LoanResult> loans = jdbc.query(LOAN_COLUMNS + " WHERE tenant_id = ?" + filter + " ORDER BY " + order.sql
                + ", loan_id LIMIT ? OFFSET ?", LOAN, page.toArray());
        return new LoanPage(loanResultsAsOf(tenantId), total == null ? 0 : total, loans, regionsOf(tenantId, loans));
    }

    private static final String REGION_COLUMNS = "region, region_high, region_members, region_metric_min, region_metric_max, "
            + "region_ecl_min, region_ecl_max, region_extrapolated";

    private static RegionInfo regionInfo(ResultSet rs) throws SQLException {
        String region = rs.getString("region");
        return region == null ? null : new RegionInfo(Region.valueOf(region), rs.getInt("region_high"), rs.getInt("region_members"),
                rs.getDouble("region_metric_min"), rs.getDouble("region_metric_max"), rs.getDouble("region_ecl_min"),
                rs.getDouble("region_ecl_max"), rs.getBoolean("region_extrapolated"));
    }

    /** The regions of the listed loans, in listing order; empty for a run that did not ask for any. */
    private Map<String, RegionInfo> regionsOf(Long tenantId, List<PortfolioRiskEngine.LoanResult> loans) {
        Map<String, RegionInfo> regions = new LinkedHashMap<>();
        if (loans.isEmpty()) {
            return regions;
        }
        List<String> ids = loans.stream().map(PortfolioRiskEngine.LoanResult::loanId).toList();
        jdbc.query("SELECT loan_id, " + REGION_COLUMNS + " FROM portfolio_risk_loan WHERE tenant_id = ? AND region IS NOT NULL "
                + "AND loan_id = ANY (?)", rs -> {
            regions.put(rs.getString("loan_id"), regionInfo(rs));
        }, tenantId, ids.toArray(new String[0]));
        return regions;
    }

    /** Whether the latest run stored regions, for the export's columns. */
    public boolean hasRegions(Long tenantId) {
        Boolean any = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM portfolio_risk_loan WHERE tenant_id = ? AND region IS NOT NULL)",
                Boolean.class, tenantId);
        return Boolean.TRUE.equals(any);
    }

    /** Every loan of the latest run in loan-id order with its region (null when none), a page at a time. */
    public void forEachLoanWithRegion(Long tenantId, java.util.function.BiConsumer<PortfolioRiskEngine.LoanResult, RegionInfo> sink) {
        String after = "";
        while (true) {
            List<Object[]> page = new ArrayList<>();
            jdbc.query(LOAN_COLUMNS_WITH_REGION + " WHERE tenant_id = ? AND loan_id > ? ORDER BY loan_id LIMIT 1000", rs -> {
                page.add(new Object[]{LOAN.mapRow(rs, 0), regionInfo(rs)});
            }, tenantId, after);
            for (Object[] row : page) {
                sink.accept((PortfolioRiskEngine.LoanResult) row[0], (RegionInfo) row[1]);
            }
            if (page.size() < 1000) {
                return;
            }
            after = ((PortfolioRiskEngine.LoanResult) page.get(page.size() - 1)[0]).loanId();
        }
    }

    /** Every loan of the latest run in loan-id order, a page at a time (keyset), for exports of any size. */
    public void forEachLoan(Long tenantId, Consumer<PortfolioRiskEngine.LoanResult> sink) {
        String after = "";
        while (true) {
            List<PortfolioRiskEngine.LoanResult> page = jdbc.query(LOAN_COLUMNS + " WHERE tenant_id = ? AND loan_id > ? ORDER BY loan_id LIMIT 1000",
                    LOAN, tenantId, after);
            page.forEach(sink);
            if (page.size() < 1000) {
                return;
            }
            after = page.get(page.size() - 1).loanId();
        }
    }

    private LocalDate loanResultsAsOf(Long tenantId) {
        return jdbc.query("SELECT max(as_of) FROM portfolio_risk_loan WHERE tenant_id = ?",
                (ResultSetExtractor<LocalDate>) rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, tenantId);
    }

    /**
     * Simulates the portfolio's one-year loss distribution from the loans of the latest run, with the
     * caller's choice of confidence level, correlation and seed. No projection is repeated, so this is as
     * quick as the simulation itself.
     *
     * @return empty when no run has stored loan results yet (or none of its loans carries default risk)
     */
    public Optional<LossDistribution> lossDistribution(Long tenantId, double confidenceLevel, int numScenarios, double assetCorrelation,
                                                       Long seed, boolean importanceSampling) {
        List<PortfolioRiskEngine.LoanResult> loans = new ArrayList<>();
        forEachLoan(tenantId, loans::add);
        CvarRequest request = PortfolioRiskEngine.lossRequest(loans, confidenceLevel, numScenarios, assetCorrelation, seed, importanceSampling);
        if (request == null) {
            return Optional.empty();
        }
        return Optional.of(new LossDistribution(loanResultsAsOf(tenantId), request.loans().size(),
                loans.stream().anyMatch(loan -> loan.weight() > 1.0), lossEngine.simulate(request)));
    }

    private static final String LOAN_COLUMNS = "SELECT loan_id, property_state, exposure, pd_12m, pd_lifetime, lgd, ecl_12m, ecl_lifetime, "
            + "ecl_ifrs9, stage, stage_reason, weight FROM portfolio_risk_loan";

    private static final String LOAN_COLUMNS_WITH_REGION = "SELECT loan_id, property_state, exposure, pd_12m, pd_lifetime, lgd, ecl_12m, "
            + "ecl_lifetime, ecl_ifrs9, stage, stage_reason, weight, " + REGION_COLUMNS + " FROM portfolio_risk_loan";

    private static final RowMapper<PortfolioRiskEngine.LoanResult> LOAN = (rs, i) -> new PortfolioRiskEngine.LoanResult(
            rs.getString("loan_id"), rs.getString("property_state"), rs.getDouble("exposure"), rs.getDouble("pd_12m"),
            rs.getDouble("pd_lifetime"), rs.getDouble("lgd"), rs.getDouble("ecl_12m"), rs.getDouble("ecl_lifetime"),
            rs.getDouble("ecl_ifrs9"), rs.getInt("stage"), rs.getString("stage_reason"), rs.getDouble("weight"));

    private static final String RUN_COLUMNS = "SELECT id, status, scenarios, loans_total, loans_done, requested_by, requested_at, "
            + "started_at, heartbeat_at, finished_at, error, attempt, deferred, request IS NOT NULL AS restartable "
            + "FROM portfolio_risk_run";

    private static final RowMapper<Run> RUN = (rs, i) -> {
        Status status = Status.valueOf(rs.getString("status"));
        String error = rs.getString("error");
        int attempt = rs.getInt("attempt");
        boolean waiting = status == Status.QUEUED && rs.getBoolean("deferred");
        boolean restartable = rs.getBoolean("restartable");
        // Stale and beyond restarting reads as failed at once; one the supervisor will restart reads as it is,
        // and so does one that is only waiting in the queue for a worker.
        boolean beyondRestarting = status == Status.RUNNING ? !(restartable && attempt < MAX_ATTEMPTS)
                : status == Status.QUEUED && !restartable;
        if (beyondRestarting && rs.getTimestamp("heartbeat_at").toInstant().isBefore(Instant.now().minus(STALE_AFTER))) {
            status = Status.FAILED;
            error = STALE_MESSAGE;
        }
        return new Run(rs.getObject("id", UUID.class), status, Arrays.asList(rs.getString("scenarios").split(",")),
                rs.getInt("loans_total"), rs.getInt("loans_done"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), error, attempt, waiting);
    };

    private static final String SNAPSHOT_COLUMNS = "as_of, scenario, loans, loans_excluded, sampled_loans, exposure, pd_12m, pd_lifetime, "
            + "ecl_12m, ecl_lifetime, ecl_ifrs9, stage1, stage2, stage3, model_version, computed_at, decision::text AS decision_json";

    private static Snapshot snapshot(ResultSet rs, JsonNode detail) throws SQLException {
        return new Snapshot(rs.getObject("as_of", LocalDate.class), rs.getString("scenario"), rs.getInt("loans"),
                rs.getInt("loans_excluded"), rs.getInt("sampled_loans"), rs.getDouble("exposure"), rs.getDouble("pd_12m"),
                rs.getDouble("pd_lifetime"), rs.getDouble("ecl_12m"), rs.getDouble("ecl_lifetime"), rs.getDouble("ecl_ifrs9"),
                rs.getInt("stage1"), rs.getInt("stage2"), rs.getInt("stage3"), rs.getString("model_version"),
                rs.getTimestamp("computed_at").toInstant(), detail, rs.getString("decision_json") == null ? null
                        : MAPPER.readTree(rs.getString("decision_json")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
