package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Deliberately NOT covered by {@code AuditAspect} (see that class's doc): the request here
 * carries a raw password (and, once 2FA is involved, a TOTP code) and the response carries a
 * live JWT -- none of that should ever be written into the generic audit trail. {@link
 * LoginAttempt} is the credential-free record of every call here instead (username + outcome
 * only), written directly, not through the generic aspect.
 *
 * Lockout and deactivation are both enforced by Spring Security itself (see SecurityConfig's
 * userDetailsService) -- this class only maintains the lockout counters and translates the
 * resulting exceptions (LockedException, DisabledException, BadCredentialsException) into
 * distinct responses. {@code @RateLimiter} adds a separate, global volume cap on top (see
 * ValidationAuditAdvice for how its rejection becomes a 429, not a 500).
 *
 * <p><b>2FA</b> is layered on top of the password check, not part of Spring Security's own
 * authentication chain (it knows nothing about a third factor) -- see {@link Role#requiresTotp()}
 * for which roles this applies to. After the password/lockout/disabled checks above succeed:
 * <ul>
 * <li>Mandatory role, not yet enrolled -> a short-lived {@link JwtService#issueSetupToken}
 * response instead of a real session (see {@code SecurityConfig} for how that token is
 * contained to only the two enrollment endpoints).
 * <li>Enrolled, correct code (or no 2FA on an optional-role account) -> a real session, as
 * before.
 * <li>Enrolled, wrong/missing code -> for a mandatory role, the *exact same* generic failure and
 * lockout increment as a wrong password (this is what makes verification atomic: omitting the
 * code must be indistinguishable from guessing wrong). For an optional role that hasn't
 * submitted a code yet, a distinct {@code mfaRequired} response instead (the two-step
 * handshake's first leg, not a failure -- no lockout increment).
 * </ul>
 *
 * <p><b>Activation</b> (CLIENT accounts created via AdminUserController's invite flow) is
 * checked before any of the above -- see the comment at the top of {@link #login} for why it
 * can't be a Spring Security exception type the way locked/disabled are.
 */
@RestController
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final OrganizationService organizationService;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final TotpService totpService;
    private final TotpSecretCipher totpSecretCipher;
    private final int maxFailedAttempts;
    private final long lockoutMinutes;
    private final com.arthadhruva.riskengine.tenant.TenantSubdomainResolver subdomainResolver;
    private final com.arthadhruva.riskengine.sso.SsoConfigService ssoConfigs;

    public AuthController(AuthenticationManager authenticationManager, UserService userService,
                           OrganizationService organizationService, JwtService jwtService,
                           LoginAttemptService loginAttemptService, TotpService totpService, TotpSecretCipher totpSecretCipher,
                           @Value("${auth.max-failed-attempts}") int maxFailedAttempts,
                           @Value("${auth.lockout-minutes}") long lockoutMinutes,
                           com.arthadhruva.riskengine.tenant.TenantSubdomainResolver subdomainResolver,
                           com.arthadhruva.riskengine.sso.SsoConfigService ssoConfigs) {
        this.ssoConfigs = ssoConfigs;
        this.subdomainResolver = subdomainResolver;
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.organizationService = organizationService;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.totpService = totpService;
        this.totpSecretCipher = totpSecretCipher;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    /** The principal Spring Security's {@code UsernamePasswordAuthenticationToken}/{@code
     * UserDetailsService} contract carries is a single string -- {@code orgId::username} packs
     * the tenant into it so {@link SecurityConfig#userDetailsService} can still resolve the right
     * row without replacing {@code DaoAuthenticationProvider}. See {@link User}'s class doc for
     * why a bare username can't be looked up on its own once it's only unique per-tenant. */
    private static String compositePrincipal(Long organizationId, String username) {
        return organizationId + "::" + username;
    }

    @RateLimiter(name = "login")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest http) {
        String slug = resolveSlug(request, http);
        if (slug == null) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }
        // Unknown/inactive org slug resolves identically to a wrong username/password below --
        // never reveal whether an organization exists, same enumeration-resistance already
        // applied to usernames. No credentials can even be checked without a resolved tenant, so
        // this returns immediately rather than attempting authenticate().
        Organization org = organizationService.resolveActiveBySlug(slug).orElse(null);
        if (org == null) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }

        try {
            TenantContext.set(org.getId());
            return loginWithinTenant(request, org);
        } finally {
            TenantContext.clear();
        }
    }

    /** Subdomain first; the legacy orgSlug field is honored only while the deprecation flag is on, and
     * must agree with the subdomain when both are present. Null means "no usable organization". */
    private String resolveSlug(LoginRequest request, jakarta.servlet.http.HttpServletRequest http) {
        String fromHost = subdomainResolver.slugFromHost(http.getHeader("Host")).orElse(null);
        String fromField = subdomainResolver.orgSlugLoginEnabled() && request.orgSlug() != null
                && !request.orgSlug().isBlank() ? request.orgSlug() : null;
        if (fromHost != null && fromField != null && !fromHost.equalsIgnoreCase(fromField)) {
            return null;
        }
        if (fromHost == null && fromField != null) {
            log.warn("Login used the deprecated orgSlug field for organization '{}'", fromField);
        }
        return fromHost != null ? fromHost : fromField;
    }

    private ResponseEntity<?> loginWithinTenant(LoginRequest request, Organization org) {
        // A not-yet-activated CLIENT (see AdminUserController#createUser / ActivationController)
        // holds an unguessable placeholder password by construction, so authenticationManager
        // .authenticate() below would *always* throw BadCredentialsException for it regardless of
        // what's submitted -- this check has to run first, or "not yet activated" could never be
        // reached. Not counted toward lockout: this is a state check, not a credential guess.
        User pending = userService.findByOrganizationAndUsername(org.getId(), request.username()).orElse(null);
        // SSO-enforced organizations refuse local login for everyone but ADMINs (break-glass). Answered with
        // the same generic failure as a wrong password so it cannot be used to probe which accounts exist.
        if (pending != null && pending.getRole() != Role.ADMIN && ssoConfigs.isEnforced(org.getId())) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }
        if (pending != null && !pending.isActivated()) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Account not yet activated. Check your invitation link."));
        }

        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                    compositePrincipal(org.getId(), request.username()), request.password()));
        } catch (LockedException e) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("error", "Account locked due to too many failed login attempts. Try again later."));
        } catch (DisabledException e) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Account disabled. Contact your administrator."));
        } catch (BadCredentialsException e) {
            recordFailure(org, request.username());
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }

        User user = userService.findByOrganizationAndUsername(org.getId(), request.username()).orElseThrow();
        boolean required = user.getRole().requiresTotp();

        if (required && !user.isTotpEnabled()) {
            JwtService.IssuedToken setup = jwtService.issueSetupToken(user.getUsername(), org.getId());
            return ResponseEntity.ok(new SetupRequiredResponse(true, setup.token(), setup.expiresAt()));
        }

        if (user.isTotpEnabled()) {
            if (request.totpCode() != null && !request.totpCode().isBlank()) {
                // A secret that fails to decrypt (e.g. TOTP_ENCRYPTION_KEY rotated/regenerated
                // since enrollment -- see TotpSecretCipher's doc) must fail the same way a wrong
                // code does, not surface as a 500: it's still a real, if unusual, "this code
                // can't be verified as correct" outcome, not a server error.
                boolean valid;
                try {
                    valid = totpService.verifyCode(totpSecretCipher.decrypt(user.getTotpSecret()), request.totpCode());
                } catch (IllegalStateException e) {
                    valid = false;
                }
                if (valid) {
                    return completeLogin(user);
                }
                recordFailure(org, request.username());
                logAttempt(request.username(), false);
                return required
                        ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"))
                        : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
            }
            if (required) {
                recordFailure(org, request.username());
                logAttempt(request.username(), false);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
            }
            return ResponseEntity.ok(new MfaRequiredResponse(true));
        }

        return completeLogin(user);
    }

    private ResponseEntity<?> completeLogin(User user) {
        user.recordSuccessfulLogin();
        userService.save(user);
        logAttempt(user.getUsername(), true);

        JwtService.IssuedToken issued = jwtService.issue(user.getUsername(), user.getRole(), user.getTenantId());
        return ResponseEntity.ok(new LoginResponse(issued.token(), user.getUsername(), user.getRole().name(), issued.expiresAt(), user.getOrganization().isSandbox()));
    }

    /** A nonexistent username has nothing to increment a counter on -- still logged (below), just
     * with no lockout state to update. */
    private void recordFailure(Organization org, String username) {
        userService.recordFailedLogin(org.getId(), username, maxFailedAttempts,
                Instant.now().plus(Duration.ofMinutes(lockoutMinutes)));
    }

    private void logAttempt(String username, boolean success) {
        loginAttemptService.record(username, success);
    }

    public record LoginRequest(String orgSlug, String username, String password, String totpCode) {
    }

    public record LoginResponse(String token, String username, String role, Instant expiresAt, boolean sandbox) {
    }

    public record MfaRequiredResponse(boolean mfaRequired) {
    }

    public record SetupRequiredResponse(boolean setupRequired, String setupToken, Instant setupTokenExpiresAt) {
    }
}
