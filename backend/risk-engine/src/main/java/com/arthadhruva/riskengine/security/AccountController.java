package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
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
 * Self-service account actions for the caller's own account (the username always comes from the
 * authenticated principal, never the request body). Not audited through the generic trail: several
 * endpoints carry a password or TOTP code, and responses carry session tokens.
 *
 * <p>Security-relevant changes (password, 2FA) revoke every other session and hand the caller a fresh
 * token, so a stolen token dies the moment its owner changes a credential.
 */
@RestController
@NotAudited
public class AccountController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final TotpSecretCipher totpSecretCipher;
    private final SecondFactor secondFactor;
    private final JwtService jwtService;
    private final AuthThrottle throttle;

    public AccountController(UserService userService, PasswordEncoder passwordEncoder, TotpService totpService,
                             TotpSecretCipher totpSecretCipher, SecondFactor secondFactor, JwtService jwtService,
                             AuthThrottle throttle) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.totpSecretCipher = totpSecretCipher;
        this.secondFactor = secondFactor;
        this.jwtService = jwtService;
        this.throttle = throttle;
    }

    private User currentUser(Authentication authentication) {
        return userService.findByOrganizationAndUsername(TenantContext.get(), authentication.getName()).orElseThrow();
    }

    /** Revokes every session of the account (including the caller's) and issues the caller a new one. */
    private AuthController.LoginResponse rotateSession(User user) {
        userService.revokeSessions(user);
        User fresh = userService.reload(user).orElseThrow();
        return AuthController.LoginResponse.of(fresh, jwtService.issueSession(fresh));
    }

    @PostMapping("/account/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        User user = currentUser(authentication);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Current password is incorrect"));
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().body(Map.of("error", "The new password must differ from the current one."));
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userService.save(user);
        return ResponseEntity.ok(Map.of("message", "Password updated. Other sessions have been signed out.",
                "session", rotateSession(user)));
    }

    /**
     * Sliding session: a still-valid session token is exchanged for a fresh one bound to the same
     * original authentication time, until the absolute session limit. An idle client simply lets its
     * token expire, which is the idle timeout.
     */
    @PostMapping("/account/session/refresh")
    public ResponseEntity<?> refresh(Authentication authentication, HttpServletRequest http) {
        Object attribute = http.getAttribute(JwtAuthenticationFilter.PARSED_TOKEN_ATTRIBUTE);
        if (!(attribute instanceof JwtService.ParsedToken parsed) || !parsed.isSession()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not a session token"));
        }
        User user = currentUser(authentication);
        try {
            return ResponseEntity.ok(AuthController.LoginResponse.of(user, jwtService.issueSession(user, parsed.authTime())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Your session has reached its maximum length. Please sign in again."));
        }
    }

    /** Ends every session of the account (all devices): session versioning has no per-token state to
     * revoke individually, and signing out everywhere is the safe default for a banking console. */
    @PostMapping("/account/logout")
    public ResponseEntity<?> logout(Authentication authentication) {
        userService.revokeSessions(currentUser(authentication));
        return ResponseEntity.ok(Map.of("message", "Signed out."));
    }

    @GetMapping("/account/2fa/status")
    public ResponseEntity<?> status(Authentication authentication) {
        User user = currentUser(authentication);
        return ResponseEntity.ok(new TotpStatusResponse(user.isTotpEnabled(), user.getRole().requiresTotp()));
    }

    /**
     * Starts enrollment with a new pending secret. Refused while 2FA is enabled: replacing an active
     * secret here needed only the session, so a stolen session token could have swapped in the
     * attacker's authenticator. Changing an enrolled device goes through disable (current code
     * required) or an administrator's reset.
     */
    @PostMapping("/account/2fa/setup")
    public ResponseEntity<?> setup(Authentication authentication) {
        User user = currentUser(authentication);
        if (user.isTotpEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "Two-factor authentication is already enabled. Disable it first (requires a current code) or ask an administrator to reset it."));
        }
        String secret = totpService.generateSecret();
        user.setTotpSecret(totpSecretCipher.encrypt(secret));
        userService.save(user);
        userService.clearTotpStep(user);
        return ResponseEntity.ok(new TotpSetupResponse(totpService.buildQrCodeDataUri(user.getUsername(), secret), secret));
    }

    @PostMapping("/account/2fa/confirm")
    public ResponseEntity<?> confirm(@Valid @RequestBody TotpCodeRequest request, Authentication authentication,
                                     HttpServletRequest http) {
        var throttled = throttle.check(AuthThrottle.Kind.SECOND_FACTOR, http);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        User user = currentUser(authentication);
        if (user.isTotpEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Two-factor authentication is already enabled."));
        }
        if (!secondFactor.verifyAndConsume(user, request.code().trim())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
        }
        user.setTotpEnabled(true);
        userService.save(user);

        boolean viaSetupToken = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch("ROLE_TOTP_SETUP"::equals);
        AuthController.LoginResponse session = rotateSession(user);
        if (viaSetupToken) {
            return ResponseEntity.ok(session);
        }
        return ResponseEntity.ok(Map.of("message", "2FA enabled. Other sessions have been signed out.", "session", session));
    }

    /** Requires a valid current code; blocked for mandatory-2FA roles (an administrator resets instead). */
    @PostMapping("/account/2fa/disable")
    public ResponseEntity<?> disable(@Valid @RequestBody TotpCodeRequest request, Authentication authentication,
                                     HttpServletRequest http) {
        var throttled = throttle.check(AuthThrottle.Kind.SECOND_FACTOR, http);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        User user = currentUser(authentication);
        if (user.getRole().requiresTotp()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "2FA is required for your role and cannot be disabled here."));
        }
        if (!user.isTotpEnabled() || !secondFactor.verifyAndConsume(user, request.code().trim())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid authentication code"));
        }
        user.clearTotp();
        userService.save(user);
        userService.clearTotpStep(user);
        return ResponseEntity.ok(Map.of("message", "2FA disabled. Other sessions have been signed out.",
                "session", rotateSession(user)));
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @StrongPassword String newPassword) {
    }

    public record TotpStatusResponse(boolean enabled, boolean required) {
    }

    public record TotpSetupResponse(String qrCodeDataUri, String secret) {
    }

    public record TotpCodeRequest(@NotBlank String code) {
    }
}
