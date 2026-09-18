package com.arthadhruva.riskengine.security;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** False only for a CLIENT created via the invite flow, until they complete
     * ActivationController#activate -- passwordHash holds a permanently-unguessable placeholder
     * until then (see AdminUserController#createUser), so the account is unusable via the normal
     * login path regardless of this flag, but AuthController checks it explicitly first to give
     * a clear "not yet activated" message instead of a generic bad-credentials failure. */
    @Column(name = "activated", nullable = false)
    private boolean activated = true;

    /** Encrypted at rest (see TotpSecretCipher) -- unlike a password this must be recoverable to
     * verify future codes, so it can't be one-way hashed. Null until enrollment; may hold a
     * pending (not-yet-confirmed) secret between /account/2fa/setup and /confirm. */
    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    /**
     * Loans a CLIENT account may view via GET /my/loans. Only meaningful for CLIENT, but not
     * enforced as such -- harmless if present on another role, simpler than a role-conditional
     * constraint for what's still a small, single-purpose field.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_loan_id", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "loan_id")
    private Set<String> loanIds = new HashSet<>();

    protected User() {
        // required by JPA
    }

    public User(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<String> getLoanIds() {
        return loanIds;
    }

    public void setLoanIds(Set<String> loanIds) {
        this.loanIds = loanIds != null ? loanIds : new HashSet<>();
    }

    public void addLoanId(String loanId) {
        this.loanIds.add(loanId);
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public boolean isCurrentlyLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public void recordFailedLogin(int maxAttempts, Instant lockUntilIfExceeded) {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = lockUntilIfExceeded;
        }
    }

    public void recordSuccessfulLogin() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Clears lockout state -- used when an admin resets a password or reactivates an account,
     * both of which are implicitly vouching the account is good again. */
    public void clearLockout() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public void setTotpEnabled(boolean totpEnabled) {
        this.totpEnabled = totpEnabled;
    }

    /** Used by admin reset-2fa and by confirm's activation path's inverse -- clears enrollment
     * entirely, so the account's next login falls back into the "not yet enrolled" branch. */
    public void clearTotp() {
        this.totpSecret = null;
        this.totpEnabled = false;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }
}
