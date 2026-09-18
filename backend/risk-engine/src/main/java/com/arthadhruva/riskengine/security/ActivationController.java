package com.arthadhruva.riskengine.security;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Completes a CLIENT invite (see AdminUserController#createUser) -- fully public, like /login
 * (SecurityConfig permits it), since the account has no usable password yet for anything else to
 * authenticate it. Unlike the TOTP setup token, the activationToken never goes through
 * JwtAuthenticationFilter/SecurityContextHolder -- it's parsed directly here, since there's no
 * "authenticated but restricted" state to represent, just "prove you hold this link."
 *
 * Deliberately NOT covered by AuditAspect (see that class's doc, and AuthController's identical
 * reasoning): the request carries the client's newly-chosen raw password, and the response
 * carries a live JWT.
 */
@RestController
public class ActivationController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public ActivationController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @RateLimiter(name = "login")
    @PostMapping("/activate")
    public ResponseEntity<?> activate(@Valid @RequestBody ActivateRequest request) {
        JwtService.ParsedToken parsed = jwtService.parse(request.activationToken()).orElse(null);
        if (parsed == null || !parsed.isActivationOnly()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired activation link."));
        }

        User user = userRepository.findByUsername(parsed.username()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired activation link."));
        }
        if (user.isActivated()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Account already activated."));
        }

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActivated(true);
        userRepository.save(user);

        JwtService.IssuedToken issued = jwtService.issue(user.getUsername(), user.getRole());
        return ResponseEntity.ok(new AuthController.LoginResponse(
                issued.token(), user.getUsername(), user.getRole().name(), issued.expiresAt()));
    }

    public record ActivateRequest(
            @NotBlank String activationToken,
            @NotBlank @StrongPassword String password
    ) {
    }
}
