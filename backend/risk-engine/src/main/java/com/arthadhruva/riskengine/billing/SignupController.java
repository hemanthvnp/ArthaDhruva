package com.arthadhruva.riskengine.billing;

import com.arthadhruva.riskengine.security.Role;
import com.arthadhruva.riskengine.security.StrongPasswordValidator;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Self-service signup: a prospect creates their own organization on a 14-day trial and becomes
 * its first ADMIN (who must enroll 2FA on first login, like every ADMIN). Public, so it sits
 * behind the anonymous per-address rate limit. The billing provider call is best-effort and can
 * never fail the signup.
 */
@RestController
public class SignupController {

    private static final Set<String> RESERVED = Set.of("platform", "legacy", "admin", "api", "www", "app", "login", "signup");
    private static final int TRIAL_DAYS = 14;

    private final OrganizationService organizations;
    private final UserService users;
    private final PlanService plans;
    private final PasswordEncoder passwordEncoder;
    private final StrongPasswordValidator passwordValidator;
    private final BillingProvider billing;

    public SignupController(OrganizationService organizations, UserService users, PlanService plans,
                            PasswordEncoder passwordEncoder, StrongPasswordValidator passwordValidator,
                            BillingProvider billing) {
        this.organizations = organizations;
        this.users = users;
        this.plans = plans;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
        this.billing = billing;
    }

    public record SignupRequest(
            @NotBlank @Size(max = 100) String organizationName,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,38}$", message = "3-39 chars: lowercase letters, digits, hyphens") String slug,
            @NotBlank @Size(min = 3, max = 50) String adminUsername,
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest request) {
        if (RESERVED.contains(request.slug()) || organizations.slugTaken(request.slug())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "That organization slug is not available"));
        }
        if (!passwordValidator.isStrong(request.password())) {
            return ResponseEntity.badRequest().body(Map.of("error", "password " + passwordValidator.policyMessage()));
        }

        int trialPlan = plans.byCode("TRIAL").orElseThrow().id();
        Organization org = organizations.create(request.slug(), request.organizationName(), false, trialPlan,
                Instant.now().plus(Duration.ofDays(TRIAL_DAYS)));

        TenantContext.set(org.getId());
        try {
            User admin = new User(org, request.adminUsername(), passwordEncoder.encode(request.password()), Role.ADMIN);
            admin.setEmail(request.email());
            users.save(admin);
        } finally {
            TenantContext.clear();
        }

        billing.createCustomer(request.organizationName(), request.email(), request.slug())
                .ifPresent(customerId -> organizations.setBillingCustomerId(org.getId(), customerId));

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "organization", request.slug(), "plan", "TRIAL", "trialDays", TRIAL_DAYS,
                "next", "Sign in with your organization slug and username; you will be asked to set up two-factor authentication."));
    }
}
