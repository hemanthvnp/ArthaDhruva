package com.arthadhruva.riskengine.security;

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

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final LoginAttemptRepository loginAttemptRepository;
    private final TotpService totpService;
    private final TotpSecretCipher totpSecretCipher;
    private final int maxFailedAttempts;
    private final long lockoutMinutes;

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository, JwtService jwtService,
                           LoginAttemptRepository loginAttemptRepository, TotpService totpService, TotpSecretCipher totpSecretCipher,
                           @Value("${auth.max-failed-attempts}") int maxFailedAttempts,
                           @Value("${auth.lockout-minutes}") long lockoutMinutes) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.loginAttemptRepository = loginAttemptRepository;
        this.totpService = totpService;
        this.totpSecretCipher = totpSecretCipher;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    @RateLimiter(name = "login")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // A not-yet-activated CLIENT (see AdminUserController#createUser / ActivationController)
        // holds an unguessable placeholder password by construction, so authenticationManager
        // .authenticate() below would *always* throw BadCredentialsException for it regardless of
        // what's submitted -- this check has to run first, or "not yet activated" could never be
        // reached. Not counted toward lockout: this is a state check, not a credential guess.
        User pending = userRepository.findByUsername(request.username()).orElse(null);
        if (pending != null && !pending.isActivated()) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Account not yet activated. Check your invitation link."));
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (LockedException e) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("error", "Account locked due to too many failed login attempts. Try again later."));
        } catch (DisabledException e) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Account disabled. Contact your administrator."));
        } catch (BadCredentialsException e) {
            recordFailure(request.username());
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }

        User user = userRepository.findByUsername(request.username()).orElseThrow();
        boolean required = user.getRole().requiresTotp();

        if (required && !user.isTotpEnabled()) {
            JwtService.IssuedToken setup = jwtService.issueSetupToken(user.getUsername());
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
                recordFailure(request.username());
                logAttempt(request.username(), false);
                return required
                        ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"))
                        : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
            }
            if (required) {
                recordFailure(request.username());
                logAttempt(request.username(), false);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
            }
            return ResponseEntity.ok(new MfaRequiredResponse(true));
        }

        return completeLogin(user);
    }

    private ResponseEntity<?> completeLogin(User user) {
        user.recordSuccessfulLogin();
        userRepository.save(user);
        logAttempt(user.getUsername(), true);

        JwtService.IssuedToken issued = jwtService.issue(user.getUsername(), user.getRole());
        return ResponseEntity.ok(new LoginResponse(issued.token(), user.getUsername(), user.getRole().name(), issued.expiresAt()));
    }

    /** A nonexistent username has nothing to increment a counter on -- still logged (below), just
     * with no lockout state to update. */
    private void recordFailure(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.recordFailedLogin(maxFailedAttempts, Instant.now().plus(Duration.ofMinutes(lockoutMinutes)));
            userRepository.save(user);
        });
    }

    private void logAttempt(String username, boolean success) {
        loginAttemptRepository.save(new LoginAttempt(username, success, Instant.now()));
    }

    public record LoginRequest(String username, String password, String totpCode) {
    }

    public record LoginResponse(String token, String username, String role, Instant expiresAt) {
    }

    public record MfaRequiredResponse(boolean mfaRequired) {
    }

    public record SetupRequiredResponse(boolean setupRequired, String setupToken, Instant setupTokenExpiresAt) {
    }
}
