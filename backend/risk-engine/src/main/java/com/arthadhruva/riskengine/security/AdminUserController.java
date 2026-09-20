package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * User provisioning and lifecycle management (ADMIN only, enforced by SecurityConfig's
 * /admin/** rule) -- there's still no self-registration, and none is planned; this is the
 * controlled path an admin uses to create staff (ANALYST/ADMIN) or client accounts, link a
 * client to their loan(s), reset a forgotten/compromised password, and deactivate or reactivate
 * an account (JwtAuthenticationFilter checks {@code enabled} on every request, so deactivation
 * takes effect immediately -- not just on the account's next login attempt).
 *
 * <p>ADMIN/ANALYST creation still takes a password directly, set by the admin -- that matches
 * real staff onboarding. CLIENT creation does not: an admin choosing a customer's password and
 * handing it over out-of-band isn't realistic, so a CLIENT is created in a pending, unusable
 * state and completes their own activation via ActivationController -- see
 * {@link #createInvitedClient}.
 *
 * Deliberately NOT covered by AuditAspect (see that class's doc, and AuthController's identical
 * reasoning): several endpoints here carry a raw password, which must never be written into the
 * generic audit trail.
 */
@RestController
public class AdminUserController {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final PasswordEncoder passwordEncoder;
    private final StrongPasswordValidator strongPasswordValidator;
    private final JwtService jwtService;
    private final String frontendUrl;
    private final com.arthadhruva.riskengine.billing.PlanService planService;

    public AdminUserController(UserService userService, OrganizationService organizationService,
                                PasswordEncoder passwordEncoder,
                                StrongPasswordValidator strongPasswordValidator, JwtService jwtService,
                                @Value("${app.frontend-url}") String frontendUrl,
                                com.arthadhruva.riskengine.billing.PlanService planService) {
        this.planService = planService;
        this.userService = userService;
        this.organizationService = organizationService;
        this.passwordEncoder = passwordEncoder;
        this.strongPasswordValidator = strongPasswordValidator;
        this.jwtService = jwtService;
        this.frontendUrl = frontendUrl;
    }

    /** Every endpoint here runs authenticated behind {@code /admin/**} (SecurityConfig), so
     * JwtAuthenticationFilter has already populated {@link TenantContext} -- this just resolves
     * the actual {@link Organization} row an ADMIN's new/managed users get attached to. */
    private Organization currentOrganization() {
        return organizationService.getReference(TenantContext.get());
    }

    /**
     * CLIENT accounts are the one case where {@code password} must be *absent*: they're invited,
     * not admin-provisioned with a password an admin then has to hand over out-of-band (see
     * ActivationController). ADMIN/ANALYST creation is unchanged -- a password is still required
     * there. Bean Validation can't express "required only for these roles" cleanly at the record
     * level, so both branches are validated manually here instead of via {@code @Valid}.
     */
    @PostMapping("/admin/users")
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        Organization org = currentOrganization();
        if (userService.findByOrganizationAndUsername(org.getId(), request.username()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username already exists: " + request.username()));
        }

        var plan = planService.planOf(org.getId());
        long seatsUsed = userService.countInOrganization(org.getId());
        if (seatsUsed >= plan.seatLimit()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Seat limit reached: " + seatsUsed + " of " + plan.seatLimit() + " seats used on the "
                            + plan.name() + " plan. Upgrade the plan to add more users.",
                    "seatLimit", plan.seatLimit(), "seatsUsed", seatsUsed));
        }

        if (request.role() == Role.CLIENT) {
            return createInvitedClient(org, request);
        }

        if (request.password() == null || !strongPasswordValidator.isStrong(request.password())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "password " + strongPasswordValidator.policyMessage()));
        }

        User user = new User(org, request.username(), passwordEncoder.encode(request.password()), request.role());
        user.setEmail(request.email());
        if (request.loanIds() != null) {
            user.setLoanIds(new HashSet<>(request.loanIds()));
        }
        userService.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("username", user.getUsername(), "role", user.getRole().name(), "loanIds", user.getLoanIds()));
    }

    private ResponseEntity<?> createInvitedClient(Organization org, CreateUserRequest request) {
        if (request.password() != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "CLIENT accounts are activated via invite -- do not set a password directly."));
        }

        User user = new User(org, request.username(), passwordEncoder.encode(UUID.randomUUID().toString()), request.role());
        user.setActivated(false);
        user.setEmail(request.email());
        if (request.loanIds() != null) {
            user.setLoanIds(new HashSet<>(request.loanIds()));
        }
        userService.save(user);

        JwtService.IssuedToken activation = jwtService.issueActivationToken(user.getUsername(), org.getId());
        String activationLink = frontendUrl + "/activate?token=" + activation.token();

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "username", user.getUsername(),
                "role", user.getRole().name(),
                "loanIds", user.getLoanIds(),
                "activationLink", activationLink));
    }

    @PostMapping("/admin/users/{username}/loans")
    public ResponseEntity<?> addLoan(@PathVariable String username, @RequestBody AddLoanRequest request) {
        return userService.findByOrganizationAndUsername(TenantContext.get(), username)
                .map(user -> {
                    user.addLoanId(request.loanId());
                    userService.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "loanIds", user.getLoanIds()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    /** No current-password check by design -- that's the point of an admin-driven reset. Also
     * clears lockout state: resetting a password is implicitly vouching the account is good
     * again. */
    @PostMapping("/admin/users/{username}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable String username, @Valid @RequestBody ResetPasswordRequest request) {
        return userService.findByOrganizationAndUsername(TenantContext.get(), username)
                .map(user -> {
                    user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
                    user.clearLockout();
                    userService.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "message", "Password reset."));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    /** Rejects deactivating the caller's own account -- otherwise an admin could lock themselves
     * out with no other admin to undo it. */
    @PostMapping("/admin/users/{username}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable String username, Authentication authentication) {
        if (username.equals(authentication.getName())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "You cannot deactivate your own account."));
        }
        return userService.findByOrganizationAndUsername(TenantContext.get(), username)
                .map(user -> {
                    user.setEnabled(false);
                    userService.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "enabled", user.isEnabled()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    /** Clears lockout state too, for the same "clean slate" reasoning as a password reset. */
    @PostMapping("/admin/users/{username}/activate")
    public ResponseEntity<?> activate(@PathVariable String username) {
        return userService.findByOrganizationAndUsername(TenantContext.get(), username)
                .map(user -> {
                    user.setEnabled(true);
                    user.clearLockout();
                    userService.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "enabled", user.isEnabled()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    /** Recovery path for a lost phone or an offboarded/compromised account -- clears enrollment
     * entirely (no code/password needed, an explicit admin action). The account's next login
     * naturally falls back into AuthController's "not yet enrolled" branch, so re-enrollment
     * uses the exact same flow as first-time setup. */
    @PostMapping("/admin/users/{username}/reset-2fa")
    public ResponseEntity<?> resetTotp(@PathVariable String username) {
        return userService.findByOrganizationAndUsername(TenantContext.get(), username)
                .map(user -> {
                    user.clearTotp();
                    userService.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "message", "2FA reset."));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    /**
     * The user directory ManageUsersPage's own doc comment used to note didn't exist -- every
     * account in the caller's own organization, so an admin can act on one without already
     * knowing its exact username. Never another tenant's accounts -- an ADMIN is scoped to their
     * own organization, same as every other admin endpoint here. Deliberately omits {@code
     * passwordHash}/{@code totpSecret}; everything else here is already visible piecemeal via
     * other admin endpoints (create/reset/deactivate all echo it back).
     */
    /** Paginated (default 100 per page, hard cap 100); the total count is in {@code X-Total-Count}. */
    @GetMapping("/admin/users")
    public ResponseEntity<List<UserSummary>> listUsers(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "100") int size) {
        var result = userService.pageInOrganization(TenantContext.get(), page, size);
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(result.getTotalElements()))
                .body(result.getContent().stream()
                        .map(u -> new UserSummary(u.getUsername(), u.getRole(), u.isEnabled(), u.isActivated(),
                                u.isTotpEnabled(), u.isCurrentlyLocked(), u.getCreatedAt(), u.getLoanIds()))
                        .toList());
    }

    public record UserSummary(
            String username, Role role, boolean enabled, boolean activated,
            boolean totpEnabled, boolean locked, java.time.Instant createdAt, Set<String> loanIds
    ) {
    }

    /** {@code password} is intentionally unvalidated here (no {@code @NotBlank}/{@code @StrongPassword})
     * -- it's required for ADMIN/ANALYST and forbidden for CLIENT, validated manually in
     * {@link #createUser} since that's conditional on {@code role}. */
    public record CreateUserRequest(
            @NotBlank String username,
            String password,
            @NotNull Role role,
            List<String> loanIds,
            @jakarta.validation.constraints.Email String email
    ) {
    }

    public record AddLoanRequest(@NotBlank String loanId) {
    }

    public record ResetPasswordRequest(@NotBlank @StrongPassword String newPassword) {
    }
}
