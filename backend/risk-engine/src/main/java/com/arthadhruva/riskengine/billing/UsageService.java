package com.arthadhruva.riskengine.billing;

import com.arthadhruva.riskengine.event.LoanScoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/** Billable usage counters, one row per tenant / month / metric, bumped with a single atomic
 * upsert so concurrent scoring calls (on any replica) never lose a count. */
@Service
public class UsageService {

    public static final String SCORE_CALL = "SCORE_CALL";
    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final JdbcTemplate jdbc;

    public UsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void increment(Long tenantId, String metric) {
        jdbc.update("INSERT INTO usage_record (tenant_id, period, metric, quantity) VALUES (?, ?, ?, 1) "
                + "ON CONFLICT (tenant_id, period, metric) DO UPDATE SET quantity = usage_record.quantity + 1",
                tenantId, YearMonth.now().toString(), metric);
    }

    /** Metering must never break the action being metered, so a failure is logged, not thrown. */
    @EventListener
    void onScored(LoanScoredEvent event) {
        try {
            increment(event.getTenantId(), SCORE_CALL);
        } catch (Exception e) {
            log.warn("Could not record usage for tenant {}", event.getTenantId(), e);
        }
    }

    public Map<String, Long> currentPeriod(Long tenantId) {
        Map<String, Long> usage = new LinkedHashMap<>();
        jdbc.query("SELECT metric, quantity FROM usage_record WHERE tenant_id = ? AND period = ? ORDER BY metric",
                rs -> {
                    usage.put(rs.getString(1), rs.getLong(2));
                }, tenantId, YearMonth.now().toString());
        return usage;
    }
}
