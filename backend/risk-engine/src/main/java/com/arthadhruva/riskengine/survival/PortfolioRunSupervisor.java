package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.scheduling.LeaderElection;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Keeps portfolio runs moving, in two parts.
 *
 * <p><b>Every replica</b> takes waiting runs from the queue whenever it has a worker free
 * ({@link PortfolioRiskService#claimQueued}): a run recorded on a busy replica, or put back in the queue,
 * is picked up by whichever replica gets to it first.
 *
 * <p><b>The leading replica only</b> looks for runs whose worker stopped reporting and puts them back in
 * the queue ({@link PortfolioRiskService#requeueStale}). The writes are conditional and would be safe from
 * any replica; one place doing it keeps it to one pass per interval however many replicas there are.
 *
 * <p>Deferred runs are held for the off-peak window when one is configured ({@code risk.runs.offpeak-hours},
 * UTC, e.g. {@code 22-6}); with none they start as soon as a replica has nothing else running.
 */
@Component
class PortfolioRunSupervisor {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRunSupervisor.class);

    private final PortfolioRiskService service;
    private final LoanCatalogService catalog;
    private final LeaderElection leader;
    private final String offPeakHours;
    private final Clock clock = Clock.systemUTC();

    PortfolioRunSupervisor(PortfolioRiskService service, LoanCatalogService catalog, LeaderElection leader,
                           @Value("${risk.runs.offpeak-hours:}") String offPeakHours) {
        this.service = service;
        this.catalog = catalog;
        this.leader = leader;
        this.offPeakHours = offPeakHours;
        inWindow(offPeakHours, 0);   // a malformed window fails the start, not the first tick
    }

    @Scheduled(fixedDelayString = "${risk.runs.poll-ms:2000}")
    void poll() {
        try {
            service.claimQueued(catalog::all, inWindow(offPeakHours, LocalTime.now(clock.withZone(ZoneOffset.UTC)).getHour()));
        } catch (Exception e) {
            log.warn("Could not look for waiting portfolio runs; will look again: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${risk.runs.supervise-ms:15000}")
    void tick() {
        if (!leader.isLeader()) {
            return;
        }
        try {
            service.requeueStale();
        } catch (Exception e) {
            log.error("Supervising portfolio runs failed; the next pass tries again", e);
        }
    }

    /**
     * Whether {@code hour} (0-23) falls in a window written {@code from-to}, the end exclusive; a window may
     * wrap midnight ({@code 22-6}). An empty window is always open.
     */
    static boolean inWindow(String window, int hour) {
        if (window == null || window.isBlank()) {
            return true;
        }
        String[] parts = window.trim().split("-");
        int from;
        int to;
        try {
            from = Integer.parseInt(parts[0].trim());
            to = Integer.parseInt(parts[1].trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("risk.runs.offpeak-hours must be written from-to in hours, e.g. 22-6: '" + window + "'");
        }
        if (parts.length != 2 || from < 0 || from > 23 || to < 0 || to > 24) {
            throw new IllegalArgumentException("risk.runs.offpeak-hours must be written from-to in hours, e.g. 22-6: '" + window + "'");
        }
        return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
    }
}
