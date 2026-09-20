package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.TenantContext;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
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
 * reasoning): several endpoints here carry a raw password or TOTP code, which must never be
 * written into the generic audit trail.
 *
 * <p>The two TOTP enrollment endpoints ({@code setup}/{@code confirm}) also accept a
 * ROLE_TOTP_SETUP token (see SecurityConfig) -- {@code authentication.getName()} resolves to the
 * right username either way, so no branching is needed except in {@link #confirm}, which issues
 * a real session token when a setup token completed the bootstrap flow.
 */
@RestController
public class AccountController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final TotpSecretCipher totpSecretCipher;
    private final JwtService jwtService;

    public AccountController(UserService userService, PasswordEncoder passwordEncoder,
                              TotpService totpService, TotpSecretCipher totpSecretCipher, JwtService jwtService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.totpSecretCipher = totpSecretCipher;
        this.jwtService = jwtService;
    }

    @PostMapping("/account/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        User user = userService.findByOrganizationAndUsername(TenantContext.get(), authentication.getName()).orElseThrow();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Current password is incorrect"));
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userService.save(user);
        return ResponseEntity.ok(Map.of("message", "Password updated."));
    }

    @GetMapping("/account/2fa/status")
    public ResponseEntity<?> status(Authentication authentication) {
        User user = userService.findByOrganizationAndUsername(TenantContext.get(), authentication.getName()).orElseThrow();
        return ResponseEntity.ok(new TotpStatusResponse(user.isTotpEnabled(), user.getRole().requiresTotp()));
    }

    /** Re-callable harmlessly before confirmation: a second call just overwrites the pending
     * (not-yet-activated) secret. */
    @PostMapping("/account/2fa/setup")
    public ResponseEntity<?> setup(Authentication authentication) {
        User user = userService.findByOrganizationAndUsername(TenantContext.get(), authentication.getName()).orElseThrow();
        String secret = totpService.generateSecret();
        user.setTotpSecret(totpSecretCipher.encrypt(secret));
        userService.save(user);
        return ResponseEntity.ok(new TotpSetupResponse(totpService.buildQrCodeDataUri(user.getUsername(), secret), secret));
    }

    @RateLimiter(name = "login")
    @PostMapping("/account/2fa/confirm")
    public ResponseEntity<?> confirm(@Valid @RequestBody TotpCodeRequest request, Authentication authentication) {
        User user = userService.findByOrganizationAndUsername(TenantContext.get(), authentication.getName()).orElseThrow();
        if (user.getTotpSecret() == null || !verifyStoredSecret(user, request.code())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
        }

        user.setTotpEnabled(true);
        userService.save(user);

        boolean viaSetupToken = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_TOTP_SETUP"::equals);
        if (viaSetupToken) {
            JwtService.IssuedToken issued = jwtService.issue(user.getUsername(), user.getRole(), user.getTenantId());
            return ResponseEntity.ok(new AuthController.LoginResponse(
                    issued.token(), user.getUsername(), user.getRole().name(), issued.expiresAt(), user.getOrganization().isSandbox()));
        }
        return ResponseEntity.ok(Map.of("message", "2FA enabled."));
    }

    /** Requires a valid current code -- proves the caller still holds the second factor, same
     * reasoning as self-service password change requiring the current password. Blocked outright
     * for mandatory-2FA roles: an admin has to reset it instead (see AdminUserController). */
    @RateLimiter(name = "login")
    @PostMapping("/account/2fa/disable")
    public ResponseEntity<?> disable(@Valid @RequestBody TotpCodeRequest request, Authentication authentication) {
        User user = userService.findByOrganizationAndUsername(TenantContext.get(), authentication.getName()).orElseThrow();
        if (user.getRole().requiresTotp()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "2FA is required for your role and cannot be disabled here."));
        }
        if (user.getTotpSecret() == null || !verifyStoredSecret(user, request.code())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
        }

        user.clearTotp();
        userService.save(user);
        return ResponseEntity.ok(Map.of("message", "2FA disabled."));
    }

    /** A secret that fails to decrypt (e.g. TOTP_ENCRYPTION_KEY rotated/regenerated since
     * enrollment -- see TotpSecretCipher's doc) must fail the same way a wrong code does, not
     * surface as a 500. */
    private boolean verifyStoredSecret(User user, String code) {
        try {
            return totpService.verifyCode(totpSecretCipher.decrypt(user.getTotpSecret()), code);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @StrongPassword String newPassword
    ) {
    }

    public record TotpStatusResponse(boolean enabled, boolean required) {
    }

    public record TotpSetupResponse(String qrCodeDataUri, String secret) {
    }

    public record TotpCodeRequest(@NotBlank String code) {
    }
}
