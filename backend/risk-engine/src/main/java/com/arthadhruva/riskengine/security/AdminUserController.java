package com.arthadhruva.riskengine.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * User provisioning and lifecycle management (ADMIN only, enforced by SecurityConfig's
 * /admin/** rule) -- there's still no self-registration, and none is planned; this is the
 * controlled path an admin uses to create staff (ANALYST/ADMIN) or client accounts, link a
 * client to their loan(s), reset a forgotten/compromised password, and deactivate or reactivate
 * an account (JwtAuthenticationFilter checks {@code enabled} on every request, so deactivation
 * takes effect immediately -- not just on the account's next login attempt).
 *
 * Deliberately NOT covered by AuditAspect (see that class's doc, and AuthController's identical
 * reasoning): several endpoints here carry a raw password, which must never be written into the
 * generic audit trail.
 */
@RestController
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/admin/users")
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username already exists: " + request.username()));
        }

        User user = new User(request.username(), passwordEncoder.encode(request.password()), request.role());
        if (request.loanIds() != null) {
            user.setLoanIds(new HashSet<>(request.loanIds()));
        }
        userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("username", user.getUsername(), "role", user.getRole().name(), "loanIds", user.getLoanIds()));
    }

    @PostMapping("/admin/users/{username}/loans")
    public ResponseEntity<?> addLoan(@PathVariable String username, @RequestBody AddLoanRequest request) {
        return userRepository.findByUsername(username)
                .map(user -> {
                    user.addLoanId(request.loanId());
                    userRepository.save(user);
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
        return userRepository.findByUsername(username)
                .map(user -> {
                    user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
                    user.clearLockout();
                    userRepository.save(user);
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
        return userRepository.findByUsername(username)
                .map(user -> {
                    user.setEnabled(false);
                    userRepository.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "enabled", user.isEnabled()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    /** Clears lockout state too, for the same "clean slate" reasoning as a password reset. */
    @PostMapping("/admin/users/{username}/activate")
    public ResponseEntity<?> activate(@PathVariable String username) {
        return userRepository.findByUsername(username)
                .map(user -> {
                    user.setEnabled(true);
                    user.clearLockout();
                    userRepository.save(user);
                    return ResponseEntity.ok(Map.of("username", user.getUsername(), "enabled", user.isEnabled()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown user: " + username)));
    }

    public record CreateUserRequest(
            @NotBlank String username,
            @NotBlank @StrongPassword String password,
            @NotNull Role role,
            List<String> loanIds
    ) {
    }

    public record AddLoanRequest(@NotBlank String loanId) {
    }

    public record ResetPasswordRequest(@NotBlank @StrongPassword String newPassword) {
    }
}
