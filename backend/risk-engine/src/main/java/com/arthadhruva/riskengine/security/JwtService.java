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
 * Issues and validates HMAC-SHA256 JWTs (username + role claim). If {@code jwt.secret} isn't
 * configured, a random signing key is generated at startup and logged as a warning: it's
 * ephemeral, so every restart invalidates all outstanding tokens -- fine for local dev, not for
 * a real deployment, where {@code JWT_SECRET} should be set explicitly (same "sensible local
 * default, real env var for anything that matters" pattern as DB_PASSWORD/NEO4J_PASSWORD).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration expiration;

    public JwtService(@Value("${jwt.secret}") String configuredSecret,
                       @Value("${jwt.expiration-hours}") long expirationHours) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            this.key = Jwts.SIG.HS256.key().build();
            log.warn("JWT_SECRET not set -- generated a random signing key for this run. "
                    + "Every existing token becomes invalid on the next restart. Set JWT_SECRET "
                    + "for a real deployment.");
        } else {
            this.key = Keys.hmacShaKeyFor(configuredSecret.getBytes(StandardCharsets.UTF_8));
        }
        this.expiration = Duration.ofHours(expirationHours);
    }

    public IssuedToken issue(String username, Role role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);
        String token = Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Empty if the token is missing, expired, malformed, or signed with a different key. */
    public Optional<ParsedToken> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new ParsedToken(claims.getSubject(), Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record ParsedToken(String username, Role role) {
    }
}
