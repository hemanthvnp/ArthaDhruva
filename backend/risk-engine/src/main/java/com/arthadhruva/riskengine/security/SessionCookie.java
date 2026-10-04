package com.arthadhruva.riskengine.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Builds the session cookie every login/refresh/activation/SSO-exchange endpoint sets, and the one
 * sign-out clears. httpOnly so the token is never readable from JavaScript (what XSS would otherwise
 * exfiltrate); SameSite=Strict so it is never sent on a cross-site request in the first place, which is the
 * first of this app's two CSRF layers (the second, a double-submit token for same-site pages, is described on
 * SecurityConfig). Mirrors
 * SsoController's txnCookie() builder, which already does exactly this for its own transaction cookie.
 */
@Component
public class SessionCookie {

    public static final String NAME = "ad_session";

    private final String publicUrl;

    public SessionCookie(@Value("${app.public-url:http://localhost:8080}") String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public ResponseCookie issue(JwtService.IssuedToken issued) {
        Duration maxAge = Duration.between(Instant.now(), issued.expiresAt());
        return build(issued.token(), maxAge.isNegative() ? Duration.ZERO : maxAge);
    }

    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(publicUrl.startsWith("https://"))
                .sameSite("Strict").path("/v1").maxAge(maxAge).build();
    }
}
