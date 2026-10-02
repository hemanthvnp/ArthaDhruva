package com.arthadhruva.riskengine.security;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The {@code security} module's public façade for {@link User} data -- {@link UserRepository} is
 * private to this package; every other module (and every controller in this one) goes through
 * here instead of injecting the repository directly. A thin façade by design: the orchestration
 * around these calls (password/2FA/lockout state machines, activation-token flows) stays in the
 * controllers that already carefully reason through it -- this class only owns the data access.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> findByOrganizationAndUsername(Long organizationId, String username) {
        return userRepository.findByOrganizationIdAndUsername(organizationId, username);
    }

    public void recordFailedLogin(Long organizationId, String username, int maxAttempts, java.time.Instant lockUntil) {
        userRepository.recordFailedLogin(organizationId, username, maxAttempts, lockUntil);
    }

    public org.springframework.data.domain.Page<User> pageInOrganization(Long organizationId, int page, int size) {
        return userRepository.findByOrganizationId(organizationId, org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), com.arthadhruva.riskengine.search.PageResult.boundedSize(size),
                org.springframework.data.domain.Sort.by("username")));
    }

    public Optional<User> findByOrganizationAndEmail(Long organizationId, String email) {
        return userRepository.findByOrganizationIdAndEmailIgnoreCase(organizationId, email);
    }

    public long countInOrganization(Long organizationId) {
        return userRepository.countByOrganizationId(organizationId);
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    /** The admin user directory for one organization. */
    public List<User> findAllInOrganization(Long organizationId) {
        return userRepository.findByOrganizationId(organizationId);
    }

    /** Revokes every outstanding session of this account (see {@link User#getSessionVersion()}). */
    public void revokeSessions(User user) {
        userRepository.bumpSessionVersion(user.getId());
    }

    /** @return true if {@code step} is newer than the last accepted TOTP step (and is now recorded). */
    public boolean claimTotpStep(User user, long step) {
        return userRepository.claimTotpStep(user.getId(), step) == 1;
    }

    public void clearTotpStep(User user) {
        userRepository.clearTotpStep(user.getId());
    }

    /** Re-reads the row, e.g. to pick up a session version bumped by an atomic update. */
    public Optional<User> reload(User user) {
        return userRepository.findById(user.getId());
    }

    public Optional<User> findBySsoIdentity(Long organizationId, String issuer, String subject) {
        return userRepository.findByOrganizationIdAndSsoIssuerAndSsoSubject(organizationId, issuer, subject);
    }

    /** An enabled, activated ANALYST/ADMIN in this tenant -- the only accounts work can be routed to. */
    public Optional<User> findActiveStaff(Long organizationId, String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByOrganizationIdAndUsername(organizationId, username)
                .filter(User::isStaff).filter(User::isEnabled).filter(User::isActivated);
    }

    /** Usernames of every account within one tenant that can see this loan via GET /my/loans. */
    public List<String> findUsernamesOwningLoan(Long organizationId, String loanId) {
        return userRepository.findByOrganizationIdAndLoanIdsContaining(organizationId, loanId).stream()
                .map(User::getUsername)
                .toList();
    }
}
