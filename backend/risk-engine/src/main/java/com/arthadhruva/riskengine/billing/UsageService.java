package com.arthadhruva.riskengine.billing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Billable usage counters, one row per tenant / month (UTC) / metric, bumped with a single atomic upsert
 * so concurrent calls on any replica never lose a count. Recording never breaks the call being metered,
 * but a lost count is never silent either: it increments {@code usage_record_failures_total}.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final JdbcTemplate jdbc;
    private final Counter failures;

    public UsageService(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.failures = Counter.builder("usage.record.failures").description("Usage increments that could not be recorded")
                .register(meters);
    }

    public void record(Long tenantId, String metric) {
        try {
            jdbc.update("INSERT INTO usage_record (tenant_id, period, metric, quantity) VALUES (?, ?, ?, 1) "
                            + "ON CONFLICT (tenant_id, period, metric) DO UPDATE SET quantity = usage_record.quantity + 1",
                    tenantId, YearMonth.now(ZoneOffset.UTC).toString(), metric);
        } catch (Exception e) {
            failures.increment();
            log.error("Could not record {} usage for tenant {}", metric, tenantId, e);
        }
    }

    public Map<String, Long> currentPeriod(Long tenantId) {
        Map<String, Long> usage = new LinkedHashMap<>();
        jdbc.query("SELECT metric, quantity FROM usage_record WHERE tenant_id = ? AND period = ? ORDER BY metric",
                rs -> {
                    usage.put(rs.getString(1), rs.getLong(2));
                }, tenantId, YearMonth.now(ZoneOffset.UTC).toString());
        return usage;
    }
}
