package com.arthadhruva.riskengine.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A credential-free record of every login attempt -- username and outcome only, never the
 * password. Written directly by AuthController (not via AuditAspect, which stays excluded from
 * AuthController entirely -- see that class's doc). Logging attempts against usernames that
 * don't exist is deliberate: that's exactly the signal an account-enumeration or credential-
 * stuffing sweep would produce.
 */
@Entity
@Table(name = "login_attempt")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoginAttempt() {
        // required by JPA
    }

    public LoginAttempt(String username, boolean success, Instant occurredAt) {
        this.username = username;
        this.success = success;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public boolean isSuccess() {
        return success;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
