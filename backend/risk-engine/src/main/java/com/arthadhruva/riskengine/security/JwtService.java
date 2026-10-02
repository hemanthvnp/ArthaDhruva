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
 * Issues and validates HMAC-SHA256 JWTs.
 *
 * <p><b>Session tokens</b> are short-lived (15 minutes by default) and carry the account's
 * {@code session_version} ({@code sv}) and the time the user actually authenticated ({@code auth_time}).
 * An active client refreshes before expiry; a refresh is refused once {@code auth_time} is older than
 * the absolute session cap (8 hours). Together that gives an idle timeout of one token lifetime, an
 * absolute timeout, and server-side revocation: bumping the account's session version invalidates
 * every outstanding token at once (see JwtAuthenticationFilter).
 *
 * <p><b>Purpose-scoped tokens</b> (TOTP enrollment, account activation, password reset, SSO state) carry
 * a {@code purpose} claim and no role; they are never accepted as sessions.
 *
 * <p>If {@code jwt.secret} is unset a random key is generated (local development only; the production
 * profile refuses to start without it, see RequiredSecretsEnvironmentPostProcessor).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String ROLE_CLAIM = "role";
    private static final String ORG_CLAIM = "org";
    private static final String SESSION_VERSION_CLAIM = "sv";
    private static final String AUTH_TIME_CLAIM = "auth_time";
    private static final String FINGERPRINT_CLAIM = "ph";

    static final String TOTP_SETUP_PURPOSE = "totp-setup";
    static final String ACTIVATION_PURPOSE = "activation";
    static final String RESET_PURPOSE = "password-reset";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration sessionMax;
    private final Duration setupExpiration;
    private final Duration activationExpiration;

    public JwtService(@Value("${jwt.secret}") String configuredSecret,
                      @Value("${jwt.access-token-minutes:15}") long accessTokenMinutes,
                      @Value("${jwt.session-max-hours:8}") long sessionMaxHours,
                      @Value("${totp.setup-token-expiration-minutes}") long setupExpirationMinutes,
                      @Value("${activation.token-expiration-hours}") long activationExpirationHours) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            this.key = Jwts.SIG.HS256.key().build();
            log.warn("JWT_SECRET not set -- generated a random signing key for this run. "
                    + "Every existing token becomes invalid on the next restart. Set JWT_SECRET "
                    + "for a real deployment.");
        } else {
            byte[] bytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits) for HS256");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
        this.accessTokenTtl = Duration.ofMinutes(accessTokenMinutes);
        this.sessionMax = Duration.ofHours(sessionMaxHours);
        this.setupExpiration = Duration.ofMinutes(setupExpirationMinutes);
        this.activationExpiration = Duration.ofHours(activationExpirationHours);
    }

    /** A fresh session for a user who has just authenticated. */
    public IssuedToken issueSession(User user) {
        return issueSession(user, Instant.now());
    }

    /**
     * A session token bound to the given authentication time: expiry is the earlier of now + the access
     * token lifetime and authTime + the absolute session cap.
     *
     * @throws IllegalStateException if the absolute session cap has already passed
     */
    public IssuedToken issueSession(User user, Instant authTime) {
        Instant now = Instant.now();
        Instant cap = authTime.plus(sessionMax);
        if (!cap.isAfter(now)) {
            throw new IllegalStateException("Session has reached its maximum lifetime");
        }
        Instant expiresAt = now.plus(accessTokenTtl).isBefore(cap) ? now.plus(accessTokenTtl) : cap;
        String token = Jwts.builder()
                .subject(user.getUsername())
                .claim(ROLE_CLAIM, user.getRole().name())
                .claim(ORG_CLAIM, user.getTenantId())
                .claim(SESSION_VERSION_CLAIM, user.getSessionVersion())
                .claim(AUTH_TIME_CLAIM, authTime.getEpochSecond())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt, cap);
    }

    public Duration sessionMax() {
        return sessionMax;
    }

    /** Short-lived token for mandatory-2FA enrollment; SecurityConfig only admits it to the two
     * enrollment endpoints. */
    public IssuedToken issueSetupToken(String username, Long organizationId) {
        return issuePurposeScopedToken(username, TOTP_SETUP_PURPOSE, setupExpiration, organizationId);
    }

    /** Invite link token; never passes JwtAuthenticationFilter, ActivationController parses it directly. */
    public IssuedToken issueActivationToken(String username, Long organizationId) {
        return issuePurposeScopedToken(username, ACTIVATION_PURPOSE, activationExpiration, organizationId);
    }

    /** Password-reset link token bound to the account's current password hash: once the password
     * changes the fingerprint no longer matches, so a used or stale link is dead without server storage. */
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
        return new IssuedToken(token, expiresAt, expiresAt);
    }

    /** A short-lived signed token with arbitrary claims for one purpose (e.g. SSO state). */
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
        return new IssuedToken(token, expiresAt, expiresAt);
    }

    /** Empty if the token is missing, expired, malformed, or signed with a different key. */
    public Optional<ParsedToken> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String purpose = claims.get(PURPOSE_CLAIM, String.class);
            Role role = purpose == null ? Role.valueOf(claims.get(ROLE_CLAIM, String.class)) : null;
            Long authTime = claims.get(AUTH_TIME_CLAIM, Long.class);
            return Optional.of(new ParsedToken(claims.getSubject(), role, purpose,
                    claims.get(ORG_CLAIM, Long.class), claims.get(SESSION_VERSION_CLAIM, Integer.class),
                    authTime == null ? null : Instant.ofEpochSecond(authTime),
                    claims.get(FINGERPRINT_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** @param sessionExpiresAt when the session can no longer be refreshed (equals expiresAt for purpose tokens) */
    public record IssuedToken(String token, Instant expiresAt, Instant sessionExpiresAt) {
    }

    public record ParsedToken(String username, Role role, String purpose, Long organizationId, Integer sessionVersion,
                              Instant authTime, String passwordFingerprint) {

        /** A real session: no purpose, a role, a tenant, a session version and an authentication time. */
        public boolean isSession() {
            return purpose == null && role != null && organizationId != null && sessionVersion != null && authTime != null;
        }

        public boolean isSetupOnly() {
            return TOTP_SETUP_PURPOSE.equals(purpose);
        }

        public boolean isActivationOnly() {
            return ACTIVATION_PURPOSE.equals(purpose);
        }

        public boolean isResetOnly() {
            return RESET_PURPOSE.equals(purpose);
        }
    }
}
