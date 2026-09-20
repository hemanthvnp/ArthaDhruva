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

    /** Usernames of every account within one tenant that can see this loan via GET /my/loans. */
    public List<String> findUsernamesOwningLoan(Long organizationId, String loanId) {
        return userRepository.findByOrganizationIdAndLoanIdsContaining(organizationId, loanId).stream()
                .map(User::getUsername)
                .toList();
    }
}
