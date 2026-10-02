package com.arthadhruva.riskengine.billing;

import com.arthadhruva.riskengine.email.EmailService;
import com.arthadhruva.riskengine.security.JwtService;
import com.arthadhruva.riskengine.security.Role;
import com.arthadhruva.riskengine.security.StrongPasswordValidator;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Platform operations across all tenants, for the {@code PLATFORM_ADMIN} role only (see
 * SecurityConfig): distinct from a tenant's own ADMIN, who can never reach this. It works on the
 * {@code organization}, {@code plan} and {@code access_request} tables, which are deliberately not
 * tenant-scoped; it has no way to read any tenant's business data, because row-level security
 * still applies to that.
 */
@RestController
public class PlatformController {

    private static final Set<String> RESERVED_SLUGS = Set.of("platform", "legacy", "admin", "api", "www", "app", "login", "signup");
    private static final String SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{2,38}$";
    private static final int PILOT_DAYS = 30;

    private final OrganizationService organizations;
    private final PlanService plans;
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final StrongPasswordValidator passwordValidator;
    private final AccessRequestRepository accessRequests;
    private final JwtService jwtService;
    private final EmailService email;
    private final BillingProvider billing;
    private final String frontendUrl;

    public PlatformController(OrganizationService organizations, PlanService plans, UserService users,
                              PasswordEncoder passwordEncoder, StrongPasswordValidator passwordValidator,
                              AccessRequestRepository accessRequests, JwtService jwtService, EmailService email,
                              BillingProvider billing, @Value("${app.frontend-url}") String frontendUrl) {
        this.organizations = organizations;
        this.plans = plans;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
        this.accessRequests = accessRequests;
        this.jwtService = jwtService;
        this.email = email;
        this.billing = billing;
        this.frontendUrl = frontendUrl;
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

    private boolean slugAvailable(String slug) {
        return slug != null && slug.matches(SLUG_PATTERN) && !RESERVED_SLUGS.contains(slug) && !organizations.slugTaken(slug);
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
        if (!slugAvailable(r.slug())) {
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

    // ---- access requests (sales-assisted onboarding) -----------------------------------------

    public record AccessRequestView(Long id, String companyName, String contactName, String workEmail, String jobTitle,
                                    String message, AccessRequest.Status status, Instant createdAt,
                                    Instant reviewedAt, String reviewedBy, Long organizationId) {
        static AccessRequestView of(AccessRequest a) {
            return new AccessRequestView(a.getId(), a.getCompanyName(), a.getContactName(), a.getWorkEmail(),
                    a.getJobTitle(), a.getMessage(), a.getStatus(), a.getCreatedAt(), a.getReviewedAt(),
                    a.getReviewedBy(), a.getOrganizationId());
        }
    }

    @GetMapping("/platform/access-requests")
    public List<AccessRequestView> listAccessRequests(@RequestParam(required = false) AccessRequest.Status status) {
        var rows = status == null ? accessRequests.findAllByOrderByCreatedAtDesc()
                : accessRequests.findByStatusOrderByCreatedAtDesc(status);
        return rows.stream().map(AccessRequestView::of).toList();
    }

    public record ApproveAccessRequest(String slug, String organizationName, String adminUsername, String plan) {
    }

    /**
     * Provisions the organization once the sales conversation (security review, pilot terms) is
     * done. The first ADMIN is invited rather than given a password: they set their own through
     * the activation link, then sign in, which forces 2FA enrollment like every ADMIN.
     */
    @PostMapping("/platform/access-requests/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, @RequestBody ApproveAccessRequest r, Authentication auth) {
        AccessRequest request = accessRequests.findById(id).orElse(null);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.getStatus() != AccessRequest.Status.PENDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Request already " + request.getStatus()));
        }
        if (!slugAvailable(r.slug())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Invalid or unavailable slug"));
        }
        if (r.adminUsername() == null || r.adminUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "adminUsername is required"));
        }
        String planCode = r.plan() == null || r.plan().isBlank() ? "TRIAL" : r.plan();
        var plan = plans.byCode(planCode);
        if (plan.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown plan " + planCode));
        }

        String orgName = r.organizationName() == null || r.organizationName().isBlank()
                ? request.getCompanyName() : r.organizationName();
        Instant trialEndsAt = "TRIAL".equals(planCode) ? Instant.now().plus(Duration.ofDays(PILOT_DAYS)) : null;
        Organization org = organizations.create(r.slug(), orgName, false, plan.get().id(), trialEndsAt);

        TenantContext.set(org.getId());
        try {
            User admin = new User(org, r.adminUsername(), passwordEncoder.encode(UUID.randomUUID().toString()), Role.ADMIN);
            admin.setActivated(false);
            admin.setEmail(request.getWorkEmail());
            users.save(admin);
        } finally {
            TenantContext.clear();
        }

        billing.createCustomer(orgName, request.getWorkEmail(), r.slug())
                .ifPresent(customerId -> organizations.setBillingCustomerId(org.getId(), customerId));

        request.approve(auth.getName(), org.getId());
        accessRequests.save(request);

        String activationLink = frontendUrl + "/activate?token="
                + jwtService.issueActivationToken(r.adminUsername(), org.getId()).token();
        boolean emailed = email.send(request.getWorkEmail(), "Your ArthaDhruva workspace is ready",
                "Hello " + request.getContactName() + ",\n\nYour organization \"" + orgName + "\" has been set up."
                        + "\nOrganization: " + r.slug() + "\nUsername: " + r.adminUsername()
                        + "\n\nSet your password here (the link expires):\n" + activationLink
                        + "\n\nAfter that, sign in and you will be asked to enrol two-factor authentication.");

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "organization", view(org), "adminUsername", r.adminUsername(),
                "activationLink", activationLink, "emailed", emailed));
    }

    @PostMapping("/platform/access-requests/{id}/decline")
    public ResponseEntity<?> decline(@PathVariable Long id, Authentication auth) {
        AccessRequest request = accessRequests.findById(id).orElse(null);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.getStatus() != AccessRequest.Status.PENDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Request already " + request.getStatus()));
        }
        request.decline(auth.getName());
        accessRequests.save(request);
        return ResponseEntity.ok(AccessRequestView.of(request));
    }
}
