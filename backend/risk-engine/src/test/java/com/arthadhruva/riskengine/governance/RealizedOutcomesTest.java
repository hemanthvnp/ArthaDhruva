package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison end to end as the application's non-owner database role: a known answer, the work happening off the
 * requesting thread, one report in flight per organization, the monthly job queueing without computing, and an organization
 * seeing none of another's outcomes or reports.
 */
class RealizedOutcomesTest extends AbstractIntegrationTest {

    @Autowired OrganizationService organizations;
    @Autowired RealizedOutcomeService service;
    @Autowired RealizedOutcomeJob job;
    @Autowired JdbcTemplate jdbc;

    private Organization org(String name) {
        return organizations.create(name + "-" + UUID.randomUUID().toString().substring(0, 8), name, false, null, null);
    }

    private <T> T as(Organization o, Supplier<T> work) {
        TenantContext.set(o.getId());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void score(Organization o, String loanId, double p, Instant computedAt) {
        as(o, () -> jdbc.update("INSERT INTO loan_score (tenant_id, loan_id, calibrated_probability, raw_probability, computed_at) VALUES (?, ?, ?, ?, ?)",
                o.getId(), loanId, p, p, Timestamp.from(computedAt)));
    }

    /** 200 loans scored 30 months ago: 20 default in month 10 (scored 0.5), 180 are seen performing today (0.02), plus the unresolvable ones. */
    private void seed(Organization o) {
        Instant scored = Instant.now().minus(30 * 30, ChronoUnit.DAYS);
        LocalDate scoredOn = scored.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<RealizedOutcomeService.OutcomeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean defaulted = i < 20;
            score(o, "L" + i, defaulted ? 0.5 : 0.02, scored);
            entries.add(new RealizedOutcomeService.OutcomeEntry("L" + i, defaulted ? RealizedOutcomeService.Outcome.DEFAULTED
                    : RealizedOutcomeService.Outcome.ACTIVE, defaulted ? scoredOn.plusMonths(10) : today));
        }
        for (int i = 0; i < 5; i++) {      // defaulted before they were scored: unresolved
            score(o, "B" + i, 0.3, scored);
            entries.add(new RealizedOutcomeService.OutcomeEntry("B" + i, RealizedOutcomeService.Outcome.DEFAULTED, scoredOn.minusDays(5)));
        }
        for (int i = 0; i < 7; i++) {      // last seen performing before the horizon ended: unresolved
            score(o, "O" + i, 0.1, scored);
            entries.add(new RealizedOutcomeService.OutcomeEntry("O" + i, RealizedOutcomeService.Outcome.ACTIVE, scoredOn.plusMonths(6)));
        }
        for (int i = 0; i < 3; i++) {      // an outcome for a loan with no stored score
            entries.add(new RealizedOutcomeService.OutcomeEntry("N" + i, RealizedOutcomeService.Outcome.DEFAULTED, scoredOn.plusMonths(2)));
        }
        as(o, () -> service.record(o.getId(), "admin", entries));
    }

    private JsonNode completed(Organization o) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RealizedOutcomeService.ReportView latest = as(o, () -> service.latest(o.getId())).orElseThrow();
            if (latest.status().equals("COMPLETED")) {
                return latest.report();
            }
            assertNotEquals("FAILED", latest.status(), latest.error());
            Thread.sleep(100);
        }
        throw new AssertionError("the report did not complete");
    }

    @Test
    void theComparisonHasTheKnownAnswerAndRunsOffTheRequestingThread() throws Exception {
        Organization a = org("Known");
        seed(a);
        String requesting = Thread.currentThread().getName();
        RealizedOutcomeService.ReportView queued = as(a, () -> service.request(a.getId(), "admin"));
        assertTrue(List.of("QUEUED", "RUNNING", "COMPLETED").contains(queued.status()), "request returns a row to poll: " + queued.status());

        JsonNode report = completed(a);
        assertEquals(24, report.get("horizonMonths").asInt());
        assertEquals(212, report.get("matchedToAScore").asInt(), "212 outcomes match a score; the 3 without a score do not");
        assertEquals(200, report.get("resolved").asInt());
        assertEquals(20, report.get("defaults").asInt());
        assertEquals(5, report.get("unresolvedDefaultedOrPrepaidBeforeScored").asInt());
        assertEquals(7, report.get("unresolvedNotObservedThroughHorizon").asInt());
        assertEquals(215, report.get("outcomesRecorded").asInt());
        assertEquals(212, report.get("loansScored").asInt());
        assertEquals(0.068, report.get("meanPredicted").asDouble(), 1e-9);
        assertEquals(0.1, report.get("realizedRate").asDouble(), 1e-9);
        assertEquals(0.68, report.get("predictedOverRealized").asDouble(), 1e-9);
        assertEquals(1.0, report.get("auc").asDouble(), 1e-9);
        assertEquals(10, report.get("deciles").size());
        assertEquals(1.0, report.get("deciles").get(9).get("realized").asDouble(), 1e-12);
        assertTrue(report.get("caveat").asString().contains("latest score"));
        String worker = report.get("computedOn").asString();
        assertTrue(worker.startsWith("risk-compute-"), "computed on a compute thread, not the scheduler or request thread: " + worker);
        assertNotEquals(requesting, worker);
    }

    @Test
    void anOrganizationHasOneReportInFlightAtATime() {
        Organization a = org("Single");
        seed(a);
        RealizedOutcomeService.ReportView first = as(a, () -> service.request(a.getId(), "admin"));
        RealizedOutcomeService.ReportView second = as(a, () -> service.request(a.getId(), "admin"));
        if (!first.status().equals("COMPLETED")) {      // while the first is in flight a second request returns it, not a new one
            assertTrue(second.id().equals(first.id()) || second.status().equals("COMPLETED"), "second " + second);
        }
        Integer inFlight = as(a, () -> jdbc.queryForObject("SELECT count(*) FROM score_calibration_report WHERE status IN ('QUEUED','RUNNING')", Integer.class));
        assertTrue(inFlight <= 1);
    }

    @Test
    void theMonthlyJobQueuesOnlyForOrganizationsWithOutcomesAndOnlyOncePerMonth() throws Exception {
        Organization withOutcomes = org("WithOutcomes");
        Organization without = org("Without");
        seed(withOutcomes);
        int first = job.queueAll();
        assertTrue(first >= 1, "the organization with outcomes is queued");
        completed(withOutcomes);
        assertEquals(0, as(without, () -> service.latest(without.getId())).map(r -> 1).orElse(0), "none for an organization with no outcomes");
        job.queueAll();
        Integer reports = as(withOutcomes, () -> jdbc.queryForObject("SELECT count(*) FROM score_calibration_report WHERE tenant_id = ?", Integer.class, withOutcomes.getId()));
        assertEquals(1, reports, "a second pass in the same month queues nothing for it");
        assertEquals(RealizedOutcomeJob.REQUESTED_BY, as(withOutcomes, () -> service.latest(withOutcomes.getId())).orElseThrow().requestedBy());
    }

    @Test
    void aReportWhoseWorkerStoppedIsClosedSoANewOneCanBeQueued() throws Exception {
        Organization a = org("Stale");
        seed(a);
        as(a, () -> jdbc.update("INSERT INTO score_calibration_report (id, tenant_id, status, requested_by, horizon_months, created_at) "
                + "VALUES (?, ?, 'RUNNING', 'admin', 24, now() - interval '2 hours')", UUID.randomUUID(), a.getId()));
        RealizedOutcomeService.ReportView fresh = as(a, () -> service.request(a.getId(), "admin"));
        assertNotEquals("FAILED", fresh.status());
        completed(a);
        Integer failed = as(a, () -> jdbc.queryForObject("SELECT count(*) FROM score_calibration_report WHERE status = 'FAILED'", Integer.class));
        assertEquals(1, failed);
    }

    @Test
    void anOrganizationSeesNoneOfAnothersOutcomesOrReports() throws Exception {
        Organization a = org("Alpha");
        Organization b = org("Beta");
        seed(a);
        as(a, () -> service.request(a.getId(), "admin"));
        completed(a);
        Integer outcomesSeenByB = as(b, () -> jdbc.queryForObject("SELECT count(*) FROM loan_outcome", Integer.class));
        Integer reportsSeenByB = as(b, () -> jdbc.queryForObject("SELECT count(*) FROM score_calibration_report", Integer.class));
        assertEquals(0, outcomesSeenByB);
        assertEquals(0, reportsSeenByB);
        assertTrue(as(b, () -> service.latest(b.getId())).isEmpty());
        assertEquals(0, as(b, () -> service.outcomeCount(b.getId())));
        // and B's own outcome for a loan id A also uses is B's, not an overwrite of A's
        as(b, () -> service.record(b.getId(), "bob", List.of(new RealizedOutcomeService.OutcomeEntry("L0", RealizedOutcomeService.Outcome.PREPAID,
                LocalDate.now(ZoneOffset.UTC).minusDays(3)))));
        assertEquals("DEFAULTED", as(a, () -> jdbc.queryForObject("SELECT outcome FROM loan_outcome WHERE loan_id = 'L0'", String.class)));
    }

    @Test
    void anOutcomeDatedInTheFutureIsRefused() {
        Organization a = org("Future");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> as(a, () -> service.record(a.getId(), "admin",
                List.of(new RealizedOutcomeService.OutcomeEntry("X", RealizedOutcomeService.Outcome.DEFAULTED, LocalDate.now(ZoneOffset.UTC).plusDays(2))))));
    }
}
