package com.arthadhruva.riskengine.billing;

import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.Map;

/** A tenant admin's view of their plan, seat usage and this month's metered usage. */
@RestController
public class UsageController {

    private final PlanService plans;
    private final UsageService usage;
    private final UserService users;

    public UsageController(PlanService plans, UsageService usage, UserService users) {
        this.plans = plans;
        this.usage = usage;
        this.users = users;
    }

    @GetMapping("/admin/usage")
    public Map<String, Object> current() {
        Long tenant = TenantContext.get();
        PlanService.Plan plan = plans.planOf(tenant);
        return Map.of(
                "plan", plan.code(),
                "seatLimit", plan.seatLimit(),
                "seatsUsed", users.countInOrganization(tenant),
                "rateLimit", Map.of("capacity", plan.rateCapacity(), "perSecond", plan.ratePerSecond()),
                "period", YearMonth.now().toString(),
                "usage", usage.currentPeriod(tenant));
    }
}
