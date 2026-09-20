package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.email.EmailService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Self-service password reset. Deliberately not covered by AuditAspect (it carries tokens and
 * passwords). Two properties worth knowing:
 * <ul>
 *   <li>The request endpoint answers identically whether or not the account exists (and whether or
 *       not an email could be sent), so it cannot be used to enumerate accounts.</li>
 *   <li>The link token is single-use without storing anything: it embeds a fingerprint of the
 *       account's current password hash, and completing the reset changes that hash.</li>
 * </ul>
 */
@RestController
public class PasswordResetController {

    private static final Map<String, String> GENERIC_OK = Map.of("message",
            "If that account exists and has an email on file, a reset link has been sent.");

    private final OrganizationService organizations;
    private final UserService users;
    private final JwtService jwt;
    private final EmailService email;
    private final PasswordEncoder passwordEncoder;
    private final StrongPasswordValidator passwordValidator;
    private final String frontendUrl;

    public PasswordResetController(OrganizationService organizations, UserService users, JwtService jwt,
                                   EmailService email, PasswordEncoder passwordEncoder,
                                   StrongPasswordValidator passwordValidator,
                                   @Value("${app.frontend-url}") String frontendUrl) {
        this.organizations = organizations;
        this.users = users;
        this.jwt = jwt;
        this.email = email;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
        this.frontendUrl = frontendUrl;
    }

    public record ResetRequest(@NotBlank String orgSlug, @NotBlank String username) {
    }

    public record ResetComplete(@NotBlank String token, @NotBlank String newPassword) {
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<?> request(@Valid @RequestBody ResetRequest request) {
        Organization org = organizations.resolveActiveBySlug(request.orgSlug()).orElse(null);
        if (org != null) {
            TenantContext.set(org.getId());
            try {
                users.findByOrganizationAndUsername(org.getId(), request.username())
                        .filter(User::isEnabled)
                        .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
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
    public ResponseEntity<?> complete(@Valid @RequestBody ResetComplete request) {
        ResponseEntity<?> invalid = ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid or expired reset link."));
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
