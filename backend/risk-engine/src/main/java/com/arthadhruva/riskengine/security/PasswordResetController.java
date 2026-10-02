package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.email.EmailService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Self-service password reset. Excluded from the generic audit trail ({@link NotAudited}): the requests
 * carry reset tokens and new passwords.
 * <ul>
 *   <li>The request endpoint answers identically whether or not the account exists, whether or not mail
 *       could be sent, and whether or not the per-account mail limit was hit -- no enumeration signal.</li>
 *   <li>The link token is single-use without server storage: it embeds a fingerprint of the current
 *       password hash, and completing the reset changes that hash.</li>
 *   <li>Completing a reset revokes every existing session of the account.</li>
 * </ul>
 */
@RestController
@NotAudited
public class PasswordResetController {

    private static final Map<String, String> GENERIC_OK = Map.of("message",
            "If that account exists and has an email on file, a reset link has been sent.");

    private final OrganizationService organizations;
    private final UserService users;
    private final JwtService jwt;
    private final EmailService email;
    private final PasswordEncoder passwordEncoder;
    private final StrongPasswordValidator passwordValidator;
    private final AuthThrottle throttle;
    private final String frontendUrl;

    public PasswordResetController(OrganizationService organizations, UserService users, JwtService jwt,
                                   EmailService email, PasswordEncoder passwordEncoder,
                                   StrongPasswordValidator passwordValidator, AuthThrottle throttle,
                                   @Value("${app.frontend-url}") String frontendUrl) {
        this.organizations = organizations;
        this.users = users;
        this.jwt = jwt;
        this.email = email;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
        this.throttle = throttle;
        this.frontendUrl = frontendUrl;
    }

    public record ResetRequest(@NotBlank @Size(max = 64) String orgSlug, @NotBlank @Size(max = 255) String username) {
    }

    public record ResetComplete(@NotBlank @Size(max = 4096) String token, @NotBlank @Size(max = 256) String newPassword) {
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<?> request(@Valid @RequestBody ResetRequest request, HttpServletRequest http) {
        var throttled = throttle.check(AuthThrottle.Kind.RESET_REQUEST, http);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        Organization org = organizations.resolveActiveBySlug(request.orgSlug()).orElse(null);
        if (org != null) {
            TenantContext.set(org.getId());
            try {
                users.findByOrganizationAndUsername(org.getId(), request.username())
                        .filter(User::isEnabled)
                        .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                        .filter(u -> throttle.allowResetMail(org.getId(), u.getUsername()))
                        .ifPresent(u -> {
                            String token = jwt.issueResetToken(u.getUsername(), org.getId(), fingerprint(u.getPasswordHash())).token();
                            email.send(u.getEmail(), "Reset your ArthaDhruva password",
                                    "Use this link within 30 minutes to choose a new password:\n"
                                            + frontendUrl + "/reset-password?token=" + token
                                            + "\n\nIf you did not ask for this, ignore this email; your password is unchanged.");
                        });
            } finally {
                TenantContext.clear();
            }
        }
        return ResponseEntity.ok(GENERIC_OK);
    }

    @PostMapping("/password-reset/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody ResetComplete request, HttpServletRequest http) {
        var throttled = throttle.check(AuthThrottle.Kind.TOKEN_REDEMPTION, http);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        ResponseEntity<?> invalid = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid or expired reset link."));
        JwtService.ParsedToken parsed = jwt.parse(request.token()).orElse(null);
        if (parsed == null || !parsed.isResetOnly() || parsed.organizationId() == null) {
            return invalid;
        }
        if (!passwordValidator.isStrong(request.newPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "password " + passwordValidator.policyMessage()));
        }
        TenantContext.set(parsed.organizationId());
        try {
            User user = users.findByOrganizationAndUsername(parsed.organizationId(), parsed.username()).orElse(null);
            if (user == null || !fingerprint(user.getPasswordHash()).equals(parsed.passwordFingerprint())) {
                return invalid;
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            user.clearLockout();
            users.save(user);
            users.revokeSessions(user);
            return ResponseEntity.ok(Map.of("message", "Password updated. You can sign in now."));
        } finally {
            TenantContext.clear();
        }
    }

    private static String fingerprint(String passwordHash) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
