package com.arthadhruva.riskengine.billing;

import com.arthadhruva.riskengine.security.Role;
import com.arthadhruva.riskengine.security.StrongPasswordValidator;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Platform operations across all tenants, for the {@code PLATFORM_ADMIN} role only (see
 * SecurityConfig): distinct from a tenant's own ADMIN, who can never reach this. It works on the
 * {@code organization} and {@code plan} tables, which are deliberately not tenant-scoped; it has
 * no way to read any tenant's business data, because row-level security still applies to that.
 */
@RestController
public class PlatformController {

    private final OrganizationService organizations;
    private final PlanService plans;
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final StrongPasswordValidator passwordValidator;

    public PlatformController(OrganizationService organizations, PlanService plans, UserService users,
                              PasswordEncoder passwordEncoder, StrongPasswordValidator passwordValidator) {
        this.organizations = organizations;
        this.plans = plans;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
    }

    public record OrgView(Long id, String slug, String name, boolean active, boolean sandbox, String plan,
                          Instant trialEndsAt, Instant createdAt) {
    }

    private OrgView view(Organization o) {
        String plan = plans.all().stream().filter(p -> java.util.Objects.equals(p.id(), o.getPlanId()))
                .map(PlanService.Plan::code).findFirst().orElse("?");
        return new OrgView(o.getId(), o.getSlug(), o.getName(), o.isActive(), o.isSandbox(), plan,
                o.getTrialEndsAt(), o.getCreatedAt());
    }

    @GetMapping("/platform/organizations")
    public List<OrgView> list() {
        return organizations.listAll().stream().map(this::view).toList();
    }

    @PostMapping("/platform/organizations/{id}/suspend")
    public ResponseEntity<?> suspend(@PathVariable Long id) {
        return organizations.setActive(id, false) ? ResponseEntity.ok(Map.of("active", false)) : ResponseEntity.notFound().build();
    }

    @PostMapping("/platform/organizations/{id}/reactivate")
    public ResponseEntity<?> reactivate(@PathVariable Long id) {
        return organizations.setActive(id, true) ? ResponseEntity.ok(Map.of("active", true)) : ResponseEntity.notFound().build();
    }

    @PostMapping("/platform/organizations/{id}/plan")
    public ResponseEntity<?> changePlan(@PathVariable Long id, @RequestParam String code) {
        var plan = plans.byCode(code);
        if (plan.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown plan " + code));
        }
        plans.assign(id, plan.get().id());
        return ResponseEntity.ok(Map.of("plan", plan.get().code()));
    }

    public record CreateSandboxRequest(String slug, String name, String adminUsername, String adminPassword) {
    }

    /** A demo organization for a prospect, flagged so the UI shows an unmistakable sandbox banner. */
    @PostMapping("/platform/sandboxes")
    public ResponseEntity<?> createSandbox(@RequestBody CreateSandboxRequest r) {
        if (r.slug() == null || !r.slug().matches("^[a-z0-9][a-z0-9-]{2,38}$") || organizations.slugTaken(r.slug())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Invalid or unavailable slug"));
        }
        if (r.adminPassword() == null || !passwordValidator.isStrong(r.adminPassword())
                || r.adminUsername() == null || r.adminUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "adminUsername and a password meeting the policy are required"));
        }
        Organization org = organizations.create(r.slug(), r.name() == null ? r.slug() : r.name(), true,
                plans.byCode("PRO").orElseThrow().id(), null);
        TenantContext.set(org.getId());
        try {
            users.save(new User(org, r.adminUsername(), passwordEncoder.encode(r.adminPassword()), Role.ADMIN));
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view(org));
    }
}
