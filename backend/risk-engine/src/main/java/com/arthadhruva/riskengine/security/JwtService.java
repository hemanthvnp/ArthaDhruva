package com.arthadhruva.riskengine.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates HMAC-SHA256 JWTs -- either a normal session token (username + role claim)
 * or a narrowly-scoped, purpose-tagged token (username + purpose claim, no role) used to
 * bootstrap either mandatory-2FA enrollment or CLIENT account activation. If {@code jwt.secret}
 * isn't configured, a random signing key is generated at startup and logged as a warning: it's
 * ephemeral, so every restart invalidates all outstanding tokens -- fine for local dev, not for
 * a real deployment, where {@code JWT_SECRET} should be set explicitly (same "sensible local
 * default, real env var for anything that matters" pattern as DB_PASSWORD/NEO4J_PASSWORD).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Claim marking a token as scoped to one narrow purpose rather than a real session -- see
     * JwtAuthenticationFilter, which assigns a distinct ROLE_TOTP_SETUP authority (no real role)
     * for a totp-setup token. An activation token never reaches JwtAuthenticationFilter at all --
     * ActivationController parses it directly, since /activate is fully public. */
    private static final String PURPOSE_CLAIM = "purpose";
    private static final String TOTP_SETUP_PURPOSE = "totp-setup";
    private static final String ACTIVATION_PURPOSE = "activation";
    private static final String RESET_PURPOSE = "password-reset";
    private static final String FINGERPRINT_CLAIM = "ph";
    private static final String ORG_CLAIM = "org";

    private final SecretKey key;
    private final Duration expiration;
    private final Duration setupExpiration;
    private final Duration activationExpiration;

    public JwtService(@Value("${jwt.secret}") String configuredSecret,
                       @Value("${jwt.expiration-hours}") long expirationHours,
                       @Value("${totp.setup-token-expiration-minutes}") long setupExpirationMinutes,
                       @Value("${activation.token-expiration-hours}") long activationExpirationHours) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            this.key = Jwts.SIG.HS256.key().build();
            log.warn("JWT_SECRET not set -- generated a random signing key for this run. "
                    + "Every existing token becomes invalid on the next restart. Set JWT_SECRET "
                    + "for a real deployment.");
        } else {
            this.key = Keys.hmacShaKeyFor(configuredSecret.getBytes(StandardCharsets.UTF_8));
        }
        this.expiration = Duration.ofHours(expirationHours);
        this.setupExpiration = Duration.ofMinutes(setupExpirationMinutes);
        this.activationExpiration = Duration.ofHours(activationExpirationHours);
    }

    public IssuedToken issue(String username, Role role, Long organizationId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);
        String token = Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .claim(ORG_CLAIM, organizationId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** A narrowly-scoped, short-lived token for the TOTP bootstrap case: a mandatory-2FA account
     * that hasn't enrolled yet has proven its password but can't get a real session until
     * enrollment completes. Carries no role claim -- JwtAuthenticationFilter assigns
     * ROLE_TOTP_SETUP instead, which SecurityConfig only permits on the two enrollment
     * endpoints. Still carries the org claim: a resumed setup flow needs to re-resolve tenant the
     * same way a normal session token does. */
    public IssuedToken issueSetupToken(String username, Long organizationId) {
        return issuePurposeScopedToken(username, TOTP_SETUP_PURPOSE, setupExpiration, organizationId);
    }

    /** A narrowly-scoped token for a CLIENT invited via AdminUserController -- the account has no
     * usable password yet, so this is the only credential that can reach ActivationController
     * until the client sets one. Longer-lived than a TOTP setup token (days, not minutes): a
     * shared link takes longer to act on than scanning a QR code on the spot. Carries the org
     * claim because /activate is fully public and never passes through JwtAuthenticationFilter --
     * ActivationController must resolve tenant from the token itself, not from a filter-populated
     * context. */
    public IssuedToken issueActivationToken(String username, Long organizationId) {
        return issuePurposeScopedToken(username, ACTIVATION_PURPOSE, activationExpiration, organizationId);
    }

    /** Password-reset link token. {@code passwordFingerprint} binds it to the account's current
     * password hash: once the password changes the fingerprint no longer matches, so a used (or
     * stale) link is dead without any server-side token storage. */
    public IssuedToken issueResetToken(String username, Long organizationId, String passwordFingerprint) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(30));
        String token = Jwts.builder()
                .subject(username)
                .claim(PURPOSE_CLAIM, RESET_PURPOSE)
                .claim(ORG_CLAIM, organizationId)
                .claim(FINGERPRINT_CLAIM, passwordFingerprint)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** A short-lived signed token carrying arbitrary claims for one purpose (e.g. the SSO round trip's
     * state). The purpose claim keeps it from being accepted as anything else. */
    public String issueClaimsToken(String purpose, Duration ttl, java.util.Map<String, Object> claims) {
        Instant now = Instant.now();
        var builder = Jwts.builder().claim(PURPOSE_CLAIM, purpose).issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)));
        claims.forEach(builder::claim);
        return builder.signWith(key).compact();
    }

    public Optional<Claims> parseClaimsToken(String token, String purpose) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return purpose.equals(claims.get(PURPOSE_CLAIM, String.class)) ? Optional.of(claims) : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private IssuedToken issuePurposeScopedToken(String username, String purpose, Duration ttl, Long organizationId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String token = Jwts.builder()
                .subject(username)
                .claim(PURPOSE_CLAIM, purpose)
                .claim(ORG_CLAIM, organizationId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Empty if the token is missing, expired, malformed, or signed with a different key. A
     * purpose-scoped token has no role claim, so {@link ParsedToken#role()} is null for it --
     * callers must check {@link ParsedToken#isSetupOnly()} / {@link ParsedToken#isActivationOnly()}
     * first. */
    public Optional<ParsedToken> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String purpose = claims.get(PURPOSE_CLAIM, String.class);
            boolean setupOnly = TOTP_SETUP_PURPOSE.equals(purpose);
            boolean activationOnly = ACTIVATION_PURPOSE.equals(purpose);
            boolean resetOnly = RESET_PURPOSE.equals(purpose);
            Role role = purpose == null ? Role.valueOf(claims.get("role", String.class)) : null;
            Long organizationId = claims.get(ORG_CLAIM, Long.class);
            return Optional.of(new ParsedToken(claims.getSubject(), role, setupOnly, activationOnly, organizationId,
                    resetOnly, claims.get(FINGERPRINT_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record ParsedToken(String username, Role role, boolean isSetupOnly, boolean isActivationOnly, Long organizationId,
                       boolean isResetOnly, String passwordFingerprint) {
    }
}
