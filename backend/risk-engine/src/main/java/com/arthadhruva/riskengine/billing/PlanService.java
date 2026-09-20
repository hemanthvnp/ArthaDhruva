package com.arthadhruva.riskengine.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscription plans and which plan an organization is on. The plan is consulted on every request
 * (rate limiting), so lookups are cached per tenant for {@code TTL_MS}: a bounded staleness on a
 * plan change in exchange for taking a database round trip off the hot path. The {@code plan}
 * and {@code organization} tables are not tenant-scoped, so no tenant context is needed here.
 */
@Service
public class PlanService {

    public record Plan(int id, String code, String name, int seatLimit, int rateCapacity, double ratePerSecond,
                       int priceCents) {
    }

    private record Cached(Plan plan, long expiresAt) {
    }

    private static final long TTL_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final Map<Long, Cached> byTenant = new ConcurrentHashMap<>();

    public PlanService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> all() {
        return jdbc.query("SELECT id, code, name, seat_limit, rate_capacity, rate_per_second, price_cents FROM plan ORDER BY id",
                (rs, i) -> new Plan(rs.getInt("id"), rs.getString("code"), rs.getString("name"), rs.getInt("seat_limit"),
                        rs.getInt("rate_capacity"), rs.getDouble("rate_per_second"), rs.getInt("price_cents")));
    }

    public Optional<Plan> byCode(String code) {
        return all().stream().filter(p -> p.code().equalsIgnoreCase(code)).findFirst();
    }

    public Plan planOf(Long organizationId) {
        long now = System.currentTimeMillis();
        Cached hit = byTenant.get(organizationId);
        if (hit != null && hit.expiresAt() > now) {
            return hit.plan();
        }
        Plan plan = jdbc.queryForObject("SELECT p.id, p.code, p.name, p.seat_limit, p.rate_capacity, p.rate_per_second, p.price_cents "
                        + "FROM organization o JOIN plan p ON p.id = o.plan_id WHERE o.id = ?",
                (rs, i) -> new Plan(rs.getInt("id"), rs.getString("code"), rs.getString("name"), rs.getInt("seat_limit"),
                        rs.getInt("rate_capacity"), rs.getDouble("rate_per_second"), rs.getInt("price_cents")), organizationId);
        byTenant.put(organizationId, new Cached(plan, now + TTL_MS));
        return plan;
    }

    public void assign(Long organizationId, int planId) {
        jdbc.update("UPDATE organization SET plan_id = ? WHERE id = ?", planId, organizationId);
        byTenant.remove(organizationId);
    }
}
