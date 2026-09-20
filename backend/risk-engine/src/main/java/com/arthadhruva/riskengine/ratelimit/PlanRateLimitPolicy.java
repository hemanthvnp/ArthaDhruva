package com.arthadhruva.riskengine.ratelimit;

import com.arthadhruva.riskengine.billing.PlanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Rate limits follow the tenant's subscription plan: a higher tier gets a bigger bucket and a
 * faster refill. Anonymous callers (login, signup) have no plan and use the configured limit. */
@Component
@Primary
class PlanRateLimitPolicy implements RateLimitPolicy {

    private final PlanService plans;
    private final Limit anonymous;

    PlanRateLimitPolicy(PlanService plans,
                        @Value("${ratelimit.anonymous.capacity:20}") int anonCapacity,
                        @Value("${ratelimit.anonymous.per-second:5}") double anonPerSecond) {
        this.plans = plans;
        this.anonymous = new Limit(anonCapacity, anonPerSecond);
    }

    @Override
    public Limit forTenant(Long tenantId) {
        PlanService.Plan plan = plans.planOf(tenantId);
        return new Limit(plan.rateCapacity(), plan.ratePerSecond());
    }

    @Override
    public Limit forAnonymous() {
        return anonymous;
    }
}
