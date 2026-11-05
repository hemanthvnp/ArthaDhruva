package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The outcomes an organization records for its loans (V32), and the report that sets its stored PD scores against them.
 *
 * <p>The report is deferred work. Asking for one, by hand or from the daily job, inserts a QUEUED row and returns; the
 * arithmetic runs on a compute thread ({@code riskComputeExecutor}), never on the request or scheduler thread. The row is
 * the claim: a compute thread takes it with a conditional update, so a row is worked once, and the unique index on
 * in-flight rows keeps an organization to one report at a time. A report whose worker died stays RUNNING or QUEUED;
 * {@link #failStale} closes it after {@link #STALE_AFTER_MINUTES} so the next request or pass can queue a new one.
 */
@Service
public class RealizedOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(RealizedOutcomeService.class);

    /** The PD model scores the probability of default within 24 months. */
    public static final int HORIZON_MONTHS = 24;
    static final int STALE_AFTER_MINUTES = 30;
    private static final int MAX_RESOLVED = 2_000_000;
    private static final String REPORT_COLUMNS = "id, status, requested_by, horizon_months, created_at, started_at, finished_at, error, report::text";

    public enum Outcome { DEFAULTED, PREPAID, ACTIVE }

    /** @param loanId one of the organization's loans; it need not be scored yet */
    public record OutcomeEntry(String loanId, Outcome outcome, LocalDate observedOn) {
    }

    public record ReportView(UUID id, String status, String requestedBy, int horizonMonths, Instant createdAt, Instant startedAt,
                             Instant finishedAt, String error, JsonNode report) {
    }

    /** @param scored how many loans have a stored score; @param recorded how many outcomes the organization has recorded */
    public record Overview(long scored, long recorded, ReportView latest) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExecutorService compute;

    public RealizedOutcomeService(JdbcTemplate jdbc, ObjectMapper mapper, @Qualifier("riskComputeExecutor") ExecutorService compute) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.compute = compute;
    }

    /** Records or replaces outcomes for the organization. */
    public int record(Long tenantId, String recordedBy, List<OutcomeEntry> entries) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (OutcomeEntry e : entries) {
            if (e.observedOn().isAfter(today)) {
                throw new IllegalArgumentException("observedOn for loan " + e.loanId() + " is in the future");
            }
        }
        jdbc.batchUpdate("INSERT INTO loan_outcome (tenant_id, loan_id, outcome, observed_on, recorded_by) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, loan_id) DO UPDATE SET outcome = EXCLUDED.outcome, observed_on = EXCLUDED.observed_on, "
                        + "recorded_by = EXCLUDED.recorded_by, recorded_at = now()",
                entries, entries.size(), (ps, e) -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, e.loanId());
                    ps.setString(3, e.outcome().name());
                    ps.setObject(4, e.observedOn());
                    ps.setString(5, recordedBy);
                });
        return entries.size();
    }

    public Overview overview(Long tenantId) {
        return new Overview(count("loan_score", tenantId), count("loan_outcome", tenantId), latest(tenantId).orElse(null));
    }

    public long outcomeCount(Long tenantId) {
        return count("loan_outcome", tenantId);
    }

    private long count(String table, Long tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenantId);
        return n == null ? 0 : n;
    }

    public Optional<ReportView> latest(Long tenantId) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM score_calibration_report WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                this::view, tenantId).stream().findFirst();
    }

    private Optional<ReportView> find(Long tenantId, UUID id) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM score_calibration_report WHERE tenant_id = ? AND id = ?", this::view, tenantId, id)
                .stream().findFirst();
    }

    /** Whether a report was requested for the organization on or after {@code since}. */
    public boolean hasReportSince(Long tenantId, LocalDate since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM score_calibration_report WHERE tenant_id = ? AND created_at >= ?",
                Integer.class, tenantId, java.sql.Date.valueOf(since));
        return n != null && n > 0;
    }

    /**
     * Queues a report and hands it to a compute thread; returns at once. With one already in flight, that one is returned and
     * nothing new is queued.
     */
    public ReportView request(Long tenantId, String requestedBy) {
        failStale(tenantId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO score_calibration_report (id, tenant_id, status, requested_by, horizon_months) VALUES (?, ?, 'QUEUED', ?, ?)",
                    id, tenantId, requestedBy, HORIZON_MONTHS);
        } catch (DuplicateKeyException inFlight) {
            return latest(tenantId).orElseThrow();
        }
        try {
            compute.execute(() -> work(tenantId, id));
        } catch (RejectedExecutionException e) {
            log.warn("The compute pool refused calibration report {}; it stays queued and is closed as stale if nothing takes it", id);
        }
        return find(tenantId, id).orElseThrow();
    }

    /** Closes reports whose worker stopped, so they stop blocking a new one. */
    void failStale(Long tenantId) {
        jdbc.update("UPDATE score_calibration_report SET status = 'FAILED', finished_at = now(), error = ? "
                        + "WHERE tenant_id = ? AND status IN ('QUEUED', 'RUNNING') AND created_at < now() - make_interval(mins => ?)",
                "The worker that took this report stopped before finishing it", tenantId, STALE_AFTER_MINUTES);
    }

    /** The computation, on a compute thread: claim the row, compare, store. */
    void work(Long tenantId, UUID id) {
        TenantContext.set(tenantId);
        try {
            if (jdbc.update("UPDATE score_calibration_report SET status = 'RUNNING', started_at = now() "
                    + "WHERE id = ? AND tenant_id = ? AND status = 'QUEUED'", id, tenantId) != 1) {
                return;      // taken by another thread, or closed meanwhile
            }
            try {
                ObjectNode report = compare(tenantId);
                jdbc.update("UPDATE score_calibration_report SET status = 'COMPLETED', finished_at = now(), report = ?::jsonb "
                        + "WHERE id = ? AND tenant_id = ?", mapper.writeValueAsString(report), id, tenantId);
            } catch (RuntimeException e) {
                log.error("Calibration report {} for tenant {} failed", id, tenantId, e);
                jdbc.update("UPDATE score_calibration_report SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ? AND tenant_id = ?",
                        String.valueOf(e.getMessage()), id, tenantId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** The resolved (probability, default) pairs, growing as rows arrive. */
    private static final class Resolved {
        double[] predicted = new double[1024];
        int[] defaulted = new int[1024];
        int size;

        void add(double p, int y) {
            if (size >= MAX_RESOLVED) {
                throw new IllegalStateException("More than " + MAX_RESOLVED + " resolved scores");
            }
            if (size == predicted.length) {
                predicted = Arrays.copyOf(predicted, size * 2);
                defaulted = Arrays.copyOf(defaulted, size * 2);
            }
            predicted[size] = p;
            defaulted[size] = y;
            size++;
        }
    }

    private ObjectNode compare(Long tenantId) {
        Resolved resolved = new Resolved();
        int[] byLabel = new int[RealizedCalibration.Label.values().length];
        int[] matched = {0};
        jdbc.query("SELECT s.calibrated_probability, s.computed_at, o.outcome, o.observed_on FROM loan_score s "
                        + "JOIN loan_outcome o ON o.tenant_id = s.tenant_id AND o.loan_id = s.loan_id WHERE s.tenant_id = ?",
                (RowCallbackHandler) rs -> {
                    matched[0]++;
                    LocalDate scoredOn = rs.getTimestamp(2).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
                    RealizedCalibration.Label label = RealizedCalibration.label(rs.getString(3), rs.getDate(4).toLocalDate(), scoredOn, HORIZON_MONTHS);
                    byLabel[label.ordinal()]++;
                    if (label == RealizedCalibration.Label.DEFAULT) {
                        resolved.add(rs.getDouble(1), 1);
                    } else if (label == RealizedCalibration.Label.NO_DEFAULT) {
                        resolved.add(rs.getDouble(1), 0);
                    }
                }, tenantId);
        RealizedCalibration.Result r = RealizedCalibration.of(Arrays.copyOf(resolved.predicted, resolved.size),
                Arrays.copyOf(resolved.defaulted, resolved.size));

        ObjectNode report = mapper.createObjectNode();
        report.put("asOf", LocalDate.now(ZoneOffset.UTC).toString());
        report.put("computedOn", Thread.currentThread().getName());
        report.put("horizonMonths", HORIZON_MONTHS);
        report.put("loansScored", count("loan_score", tenantId));
        report.put("outcomesRecorded", count("loan_outcome", tenantId));
        report.put("matchedToAScore", matched[0]);
        report.put("resolved", r.resolved());
        report.put("defaults", r.defaults());
        report.put("unresolvedDefaultedOrPrepaidBeforeScored", byLabel[RealizedCalibration.Label.BEFORE_SCORE.ordinal()]);
        report.put("unresolvedNotObservedThroughHorizon", byLabel[RealizedCalibration.Label.STILL_OPEN.ordinal()]);
        report.put("meanPredicted", r.meanPredicted());
        report.put("realizedRate", r.realizedRate());
        if (r.predictedOverRealized() == null) {
            report.putNull("predictedOverRealized");
        } else {
            report.put("predictedOverRealized", r.predictedOverRealized());
        }
        if (r.auc() == null) {
            report.putNull("auc");
        } else {
            report.put("auc", r.auc());
        }
        ArrayNode deciles = report.putArray("deciles");
        for (RealizedCalibration.Decile d : r.deciles()) {
            ObjectNode node = deciles.addObject();
            node.put("decile", d.decile());
            node.put("loans", d.loans());
            node.put("predicted", d.predicted());
            node.put("realized", d.realized());
        }
        if (r.note() == null) {
            report.putNull("note");
        } else {
            report.put("note", r.note());
        }
        report.put("method", "Each loan's latest stored score against the outcome the organization recorded for it. A default within "
                + HORIZON_MONTHS + " months of the day it was scored is a default; a default after that, a prepayment, or being last seen "
                + "performing through the horizon is not. Outcomes that cannot resolve a score are counted, not guessed.");
        report.put("caveat", "loan_score keeps only the latest score of a loan, so this is each loan's most recent score against what "
                + "followed it, not a record of every score the model has issued. The outcomes are as recorded by the organization.");
        return report;
    }

    private ReportView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String json = rs.getString(9);
        return new ReportView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), instant(rs.getTimestamp(5)),
                instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)), rs.getString(8), json == null ? null : mapper.readTree(json));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
