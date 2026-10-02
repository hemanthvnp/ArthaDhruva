package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Completes an invite (a CLIENT from AdminUserController#createUser, or an organization's first ADMIN
 * from PlatformController's access-request approval). Fully public: the account has no usable password
 * yet, so the activation token is the credential and is parsed here directly. Not audited through the
 * generic trail (the request carries the new password; the response may carry a session).
 */
@RestController
@NotAudited
public class ActivationController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthThrottle throttle;
    private final SessionCookie sessionCookie;

    public ActivationController(UserService userService, PasswordEncoder passwordEncoder, JwtService jwtService,
                                AuthThrottle throttle, SessionCookie sessionCookie) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.sessionCookie = sessionCookie;
    }

    @PostMapping("/activate")
    public ResponseEntity<?> activate(@Valid @RequestBody ActivateRequest request, HttpServletRequest http) {
        var throttled = throttle.check(AuthThrottle.Kind.TOKEN_REDEMPTION, http);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        JwtService.ParsedToken parsed = jwtService.parse(request.activationToken()).orElse(null);
        if (parsed == null || !parsed.isActivationOnly() || parsed.organizationId() == null) {
            return invalid();
        }
        // No filter populated the tenant for this public endpoint: take it from the token's own claim.
        try {
            TenantContext.set(parsed.organizationId());
            return activateWithinTenant(parsed, request);
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseEntity<?> activateWithinTenant(JwtService.ParsedToken parsed, ActivateRequest request) {
        User user = userService.findByOrganizationAndUsername(parsed.organizationId(), parsed.username()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return invalid();
        }
        if (user.isActivated()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Account already activated."));
        }
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActivated(true);
        userService.save(user);

        // A session here would let an invited ADMIN skip mandatory 2FA enrollment; they sign in instead,
        // which issues the enrollment-only token.
        if (user.getRole().requiresTotp()) {
            return ResponseEntity.ok(new SignInRequiredResponse(true, user.getOrganization().getSlug(), user.getUsername()));
        }
        JwtService.IssuedToken issued = jwtService.issueSession(user);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie.issue(issued).toString())
                .body(AuthController.LoginResponse.of(user, issued));
    }

    private static ResponseEntity<?> invalid() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or expired activation link."));
    }

    public record SignInRequiredResponse(boolean signInRequired, String organization, String username) {
    }

    public record ActivateRequest(@NotBlank String activationToken, @NotBlank @StrongPassword String password) {
    }
}
