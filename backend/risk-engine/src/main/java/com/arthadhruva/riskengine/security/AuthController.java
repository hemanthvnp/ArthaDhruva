package com.arthadhruva.riskengine.security;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
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
 * carries a raw password and the response carries a live JWT -- neither should ever be written
 * into the generic audit trail. {@link LoginAttempt} is the credential-free record of every call
 * here instead (username + outcome only), written directly, not through the generic aspect.
 *
 * Lockout is enforced by Spring Security itself (see SecurityConfig's userDetailsService) --
 * this class only maintains the counters and translates the resulting exceptions into distinct
 * responses. {@code @RateLimiter} adds a separate, global volume cap on top (see
 * ValidationAuditAdvice for how its rejection becomes a 429, not a 500).
 */
@RestController
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final LoginAttemptRepository loginAttemptRepository;
    private final int maxFailedAttempts;
    private final long lockoutMinutes;

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository, JwtService jwtService,
                           LoginAttemptRepository loginAttemptRepository,
                           @Value("${auth.max-failed-attempts}") int maxFailedAttempts,
                           @Value("${auth.lockout-minutes}") long lockoutMinutes) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.loginAttemptRepository = loginAttemptRepository;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    @RateLimiter(name = "login")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (LockedException e) {
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(Map.of("error", "Account locked due to too many failed login attempts. Try again later."));
        } catch (BadCredentialsException e) {
            recordFailure(request.username());
            logAttempt(request.username(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }

        User user = userRepository.findByUsername(request.username()).orElseThrow();
        user.recordSuccessfulLogin();
        userRepository.save(user);
        logAttempt(request.username(), true);

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

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, String role, Instant expiresAt) {
    }
}
