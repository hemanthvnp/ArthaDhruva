package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
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
 * Password login. Never audited through the generic trail (the request carries a password, the
 * response a session token); {@link LoginAttempt} is the credential-free record of every call.
 *
 * <p>Lockout and deactivation are enforced by Spring Security itself (SecurityConfig's
 * userDetailsService); this class maintains the lockout counter and translates the outcomes. Floods are
 * throttled per client address ({@link AuthThrottle}), not by a global cap that attackers could use to
 * lock everyone out.
 *
 * <p><b>2FA</b> runs after the password check -- see {@link Role#requiresTotp()}:
 * <ul>
 * <li>Mandatory role, not yet enrolled: a short-lived enrollment-only token instead of a session.
 * <li>Enrolled, correct and unused code: a session. Codes are single-use (see {@link SecondFactor}).
 * <li>Enrolled, wrong or missing code: for a mandatory role, the exact same generic failure and lockout
 * increment as a wrong password; for an optional role that has not sent a code yet, {@code mfaRequired}.
 * </ul>
 */
@RestController
@NotAudited
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);
    private static final Map<String, String> INVALID = Map.of("error", "Invalid username or password");

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final OrganizationService organizationService;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final SecondFactor secondFactor;
    private final AuthThrottle throttle;
    private final int maxFailedAttempts;
    private final long lockoutMinutes;
    private final com.arthadhruva.riskengine.tenant.TenantSubdomainResolver subdomainResolver;
    private final com.arthadhruva.riskengine.sso.SsoConfigService ssoConfigs;

    public AuthController(AuthenticationManager authenticationManager, UserService userService,
                          OrganizationService organizationService, JwtService jwtService,
                          LoginAttemptService loginAttemptService, SecondFactor secondFactor, AuthThrottle throttle,
                          @Value("${auth.max-failed-attempts}") int maxFailedAttempts,
                          @Value("${auth.lockout-minutes}") long lockoutMinutes,
                          com.arthadhruva.riskengine.tenant.TenantSubdomainResolver subdomainResolver,
                          com.arthadhruva.riskengine.sso.SsoConfigService ssoConfigs) {
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.organizationService = organizationService;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.secondFactor = secondFactor;
        this.throttle = throttle;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
        this.subdomainResolver = subdomainResolver;
        this.ssoConfigs = ssoConfigs;
    }

    /** {@code orgId::username}: Spring Security's UserDetailsService contract carries a single string,
     * and a username is only unique within a tenant (see {@link User}). */
    private static String compositePrincipal(Long organizationId, String username) {
        return organizationId + "::" + username;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        var throttled = throttle.check(AuthThrottle.Kind.LOGIN, http);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        if (request == null || request.username() == null || request.password() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID);
        }
        String slug = resolveSlug(request, http);
        // Unknown or suspended organization resolves identically to a wrong password: never reveal
        // whether an organization exists.
        Organization org = slug == null ? null : organizationService.resolveActiveBySlug(slug).orElse(null);
        if (org == null) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID);
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
    private String resolveSlug(LoginRequest request, HttpServletRequest http) {
        String fromHost = subdomainResolver.slugFromHost(http.getHeader("Host")).orElse(null);
        String fromField = subdomainResolver.orgSlugLoginEnabled() && request.orgSlug() != null
                && !request.orgSlug().isBlank() ? request.orgSlug() : null;
        if (fromHost != null && fromField != null && !fromHost.equalsIgnoreCase(fromField)) {
            return null;
        }
        if (fromHost == null && fromField != null) {
            log.debug("Login used the deprecated orgSlug field for organization '{}'", fromField);
        }
        return fromHost != null ? fromHost : fromField;
    }

    private ResponseEntity<?> loginWithinTenant(LoginRequest request, Organization org) {
        User pending = userService.findByOrganizationAndUsername(org.getId(), request.username()).orElse(null);
        // SSO-enforced organizations refuse local login for everyone but ADMINs (break-glass), with the
        // same generic failure as a wrong password so it cannot be used to probe which accounts exist.
        if (pending != null && pending.getRole() != Role.ADMIN && ssoConfigs.isEnforced(org.getId())) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID);
        }
        // A not-yet-activated account holds an unguessable placeholder password, so authenticate() below
        // would always fail for it; this state check runs first to give the actionable message.
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Account disabled. Contact your administrator."));
        } catch (BadCredentialsException e) {
            recordFailure(org, request.username());
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID);
        }

        User user = userService.findByOrganizationAndUsername(org.getId(), request.username()).orElseThrow();
        boolean required = user.getRole().requiresTotp();

        if (required && !user.isTotpEnabled()) {
            JwtService.IssuedToken setup = jwtService.issueSetupToken(user.getUsername(), org.getId());
            return ResponseEntity.ok(new SetupRequiredResponse(true, setup.token(), setup.expiresAt()));
        }

        if (user.isTotpEnabled()) {
            if (request.totpCode() != null && !request.totpCode().isBlank()) {
                if (secondFactor.verifyAndConsume(user, request.totpCode().trim())) {
                    return completeLogin(user);
                }
                recordFailure(org, request.username());
                logAttempt(request.username(), false);
                return required
                        ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID)
                        : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
            }
            if (required) {
                recordFailure(org, request.username());
                logAttempt(request.username(), false);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID);
            }
            return ResponseEntity.ok(new MfaRequiredResponse(true));
        }

        return completeLogin(user);
    }

    private ResponseEntity<?> completeLogin(User user) {
        user.recordSuccessfulLogin();
        userService.save(user);
        logAttempt(user.getUsername(), true);
        return ResponseEntity.ok(LoginResponse.of(user, jwtService.issueSession(user)));
    }

    /** A nonexistent username has nothing to increment; it is still logged. */
    private void recordFailure(Organization org, String username) {
        userService.recordFailedLogin(org.getId(), username, maxFailedAttempts,
                Instant.now().plus(Duration.ofMinutes(lockoutMinutes)));
    }

    private void logAttempt(String username, boolean success) {
        loginAttemptService.record(username, success);
    }

    public record LoginRequest(String orgSlug, String username, String password, String totpCode) {
    }

    /**
     * @param expiresAt        when this access token expires (refresh before it)
     * @param sessionExpiresAt when the session reaches its absolute limit and a new login is required
     */
    public record LoginResponse(String token, String username, String role, Instant expiresAt,
                                Instant sessionExpiresAt, boolean sandbox) {
        public static LoginResponse of(User user, JwtService.IssuedToken issued) {
            return new LoginResponse(issued.token(), user.getUsername(), user.getRole().name(), issued.expiresAt(),
                    issued.sessionExpiresAt(), user.getOrganization().isSandbox());
        }
    }

    public record MfaRequiredResponse(boolean mfaRequired) {
    }

    public record SetupRequiredResponse(boolean setupRequired, String setupToken, Instant setupTokenExpiresAt) {
    }
}
