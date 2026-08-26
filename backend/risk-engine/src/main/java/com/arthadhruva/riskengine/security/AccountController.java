package com.arthadhruva.riskengine.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Self-service actions available to any authenticated role (see SecurityConfig's /account/**
 * rule). The acting username always comes from the injected {@link Authentication}, never from
 * the request body -- so this can only ever act on the caller's own account.
 *
 * Deliberately NOT covered by AuditAspect (see that class's doc, and AuthController's identical
 * reasoning): the request here carries both the caller's current and new raw passwords, which
 * must never be written into the generic audit trail.
 */
@RestController
public class AccountController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/account/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName()).orElseThrow();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Current password is incorrect"));
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password updated."));
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @StrongPassword String newPassword
    ) {
    }
}
