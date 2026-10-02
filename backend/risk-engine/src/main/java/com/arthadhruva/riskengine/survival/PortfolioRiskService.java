package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.cvar.CvarResult;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs portfolio risk projections in the background and keeps their results.
 *
 * <p>A projection of a few thousand loans takes longer than a request should, so a run is a row in
 * {@code portfolio_risk_run}: the request that starts it returns at once, the client polls the row, and a
 * worker moves it QUEUED, RUNNING, then COMPLETED or FAILED while reporting progress. The row lives in
 * the database, not in this JVM, so any replica answers the poll.
 *
 * <p><b>One active run per organization</b> is enforced by a partial unique index, not by a check
 * here: two concurrent requests cannot both insert an active row, on any number of replicas.
 *
 * <p><b>A worker that dies</b> (deploy, crash) leaves its row RUNNING. Workers refresh
 * {@code heartbeat_at} as they go; a run whose heartbeat is older than {@link #STALE_AFTER} is reported as
 * failed and no longer blocks a new one.
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
    private static final long HEARTBEAT_EVERY_MILLIS = 2_000;
    private static final String STALE_MESSAGE = "The server stopped before the run finished; start it again.";

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    public record Run(UUID id, Status status, List<String> scenarios, int loansTotal, int loansDone, String requestedBy,
                      Instant requestedAt, Instant startedAt, Instant finishedAt, String error) {
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
                           int stage1, int stage2, int stage3, String modelVersion, Instant computedAt, JsonNode detail) {
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

    /** Every run slot and queue place is taken. */
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
     */
    public record LoanPage(LocalDate asOf, int total, List<PortfolioRiskEngine.LoanResult> loans) {
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
    private final TransactionTemplate transactions;
    private final PortfolioRiskEngine engine;
    private final CvarEngine lossEngine;
    private final ExecutorService runs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Timer runTimer;

    public PortfolioRiskService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, PortfolioRiskEngine engine,
                                CvarEngine lossEngine, @Qualifier("riskRunExecutor") ExecutorService runs, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.engine = engine;
        this.lossEngine = lossEngine;
        this.runs = runs;
        this.runTimer = Timer.builder("risk.portfolio.run").description("Portfolio risk runs, start to stored result")
                .register(meters);
    }

    /**
     * Queues a run over {@code loans} and returns its row. The tenant context of the caller must be set.
     *
     * @throws RunInProgressException if the organization already has an active run
     * @throws BusyException          if no worker or queue place is free
     */
    public Run start(Long tenantId, String requestedBy, List<LoanFeatures> loans, List<Scenario> scenarios) {
        failStaleRuns(tenantId);
        UUID id = UUID.randomUUID();
        String names = String.join(",", scenarios.stream().map(Scenario::name).toList());
        try {
            jdbc.update("INSERT INTO portfolio_risk_run (id, tenant_id, requested_by, status, scenarios, loans_total) "
                    + "VALUES (?, ?, ?, 'QUEUED', ?, ?)", id, tenantId, requestedBy, names, loans.size());
        } catch (DuplicateKeyException e) {
            throw new RunInProgressException(latest(tenantId).orElseThrow());
        }
        try {
            runs.execute(() -> execute(tenantId, id, loans, scenarios));
        } catch (RejectedExecutionException e) {
            jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND tenant_id = ?",
                    "The risk engine was busy", id, tenantId);
            throw new BusyException();
        }
        return find(tenantId, id).orElseThrow();
    }

    /** Runs on the calling thread (scheduled jobs): no run row, only the snapshots. */
    public PortfolioRiskEngine.PortfolioRisk runNow(Long tenantId, List<LoanFeatures> loans, List<Scenario> scenarios) {
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        PortfolioRiskEngine.PortfolioRisk result = engine.run(loans, scenarios, seed(tenantId, asOf), done -> { });
        transactions.executeWithoutResult(tx -> store(tenantId, asOf, result));
        return result;
    }

    private void execute(Long tenantId, UUID id, List<LoanFeatures> loans, List<Scenario> scenarios) {
        TenantContext.set(tenantId);
        Timer.Sample sample = Timer.start();
        try {
            jdbc.update("UPDATE portfolio_risk_run SET status = 'RUNNING', started_at = now(), heartbeat_at = now() "
                    + "WHERE id = ? AND tenant_id = ?", id, tenantId);
            LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
            AtomicLong lastBeat = new AtomicLong(System.currentTimeMillis());
            PortfolioRiskEngine.PortfolioRisk result = engine.run(loans, scenarios, seed(tenantId, asOf), done -> {
                long now = System.currentTimeMillis();
                if (now - lastBeat.get() >= HEARTBEAT_EVERY_MILLIS) {
                    lastBeat.set(now);
                    jdbc.update("UPDATE portfolio_risk_run SET loans_done = ?, heartbeat_at = now() WHERE id = ? AND tenant_id = ?",
                            done, id, tenantId);
                }
            });
            transactions.executeWithoutResult(tx -> {
                store(tenantId, asOf, result);
                jdbc.update("UPDATE portfolio_risk_run SET status = 'COMPLETED', loans_done = loans_total, heartbeat_at = now(), "
                        + "finished_at = now() WHERE id = ? AND tenant_id = ?", id, tenantId);
            });
        } catch (RuntimeException e) {
            log.error("Portfolio risk run {} failed for tenant {}", id, tenantId, e);
            try {
                jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND tenant_id = ?",
                        "The run failed; the error has been logged.", id, tenantId);
            } catch (RuntimeException secondary) {
                log.error("Could not mark portfolio risk run {} as failed", id, secondary);
            }
        } finally {
            sample.stop(runTimer);
            TenantContext.clear();
        }
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

    /** The same sample for every run of an organization on a given day, a new one the next day. */
    private static long seed(Long tenantId, LocalDate asOf) {
        return tenantId * 1_000_003L + asOf.toEpochDay();
    }

    private void failStaleRuns(Long tenantId) {
        jdbc.update("UPDATE portfolio_risk_run SET status = 'FAILED', error = ?, finished_at = now() "
                        + "WHERE tenant_id = ? AND status IN ('QUEUED', 'RUNNING') AND heartbeat_at < ?",
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

    /** The latest run's baseline results, loan by loan. */
    public LoanPage loans(Long tenantId, Integer stage, LoanOrder order, int limit, int offset) {
        List<Object> where = new ArrayList<>();
        where.add(tenantId);
        String filter = "";
        if (stage != null) {
            filter = " AND stage = ?";
            where.add(stage);
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM portfolio_risk_loan WHERE tenant_id = ?" + filter, Integer.class,
                where.toArray());
        List<Object> page = new ArrayList<>(where);
        page.add(limit);
        page.add(offset);
        List<PortfolioRiskEngine.LoanResult> loans = jdbc.query(LOAN_COLUMNS + " WHERE tenant_id = ?" + filter + " ORDER BY " + order.sql
                + ", loan_id LIMIT ? OFFSET ?", LOAN, page.toArray());
        return new LoanPage(loanResultsAsOf(tenantId), total == null ? 0 : total, loans);
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

    private static final RowMapper<PortfolioRiskEngine.LoanResult> LOAN = (rs, i) -> new PortfolioRiskEngine.LoanResult(
            rs.getString("loan_id"), rs.getString("property_state"), rs.getDouble("exposure"), rs.getDouble("pd_12m"),
            rs.getDouble("pd_lifetime"), rs.getDouble("lgd"), rs.getDouble("ecl_12m"), rs.getDouble("ecl_lifetime"),
            rs.getDouble("ecl_ifrs9"), rs.getInt("stage"), rs.getString("stage_reason"), rs.getDouble("weight"));

    private static final String RUN_COLUMNS = "SELECT id, status, scenarios, loans_total, loans_done, requested_by, requested_at, "
            + "started_at, heartbeat_at, finished_at, error FROM portfolio_risk_run";

    private static final RowMapper<Run> RUN = (rs, i) -> {
        Status status = Status.valueOf(rs.getString("status"));
        String error = rs.getString("error");
        boolean active = status == Status.QUEUED || status == Status.RUNNING;
        if (active && rs.getTimestamp("heartbeat_at").toInstant().isBefore(Instant.now().minus(STALE_AFTER))) {
            status = Status.FAILED;
            error = STALE_MESSAGE;
        }
        return new Run(rs.getObject("id", UUID.class), status, Arrays.asList(rs.getString("scenarios").split(",")),
                rs.getInt("loans_total"), rs.getInt("loans_done"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), error);
    };

    private static final String SNAPSHOT_COLUMNS = "as_of, scenario, loans, loans_excluded, sampled_loans, exposure, pd_12m, pd_lifetime, "
            + "ecl_12m, ecl_lifetime, ecl_ifrs9, stage1, stage2, stage3, model_version, computed_at";

    private static Snapshot snapshot(ResultSet rs, JsonNode detail) throws SQLException {
        return new Snapshot(rs.getObject("as_of", LocalDate.class), rs.getString("scenario"), rs.getInt("loans"),
                rs.getInt("loans_excluded"), rs.getInt("sampled_loans"), rs.getDouble("exposure"), rs.getDouble("pd_12m"),
                rs.getDouble("pd_lifetime"), rs.getDouble("ecl_12m"), rs.getDouble("ecl_lifetime"), rs.getDouble("ecl_ifrs9"),
                rs.getInt("stage1"), rs.getInt("stage2"), rs.getInt("stage3"), rs.getString("model_version"),
                rs.getTimestamp("computed_at").toInstant(), detail);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
