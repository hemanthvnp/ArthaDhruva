package com.arthadhruva.riskengine.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * The only safe way to look up a user by username -- {@code username} is unique only within
     * a tenant (see {@link User}'s class doc), so a bare {@code findByUsername} would be
     * ambiguous the moment two organizations share a common username. There is deliberately no
     * global {@code findByUsername} on this repository; every call site resolves the caller's
     * tenant first (via {@code TenantContext}, an already-parsed JWT, or a just-resolved
     * {@code Organization}) and passes it here explicitly.
     */
    Optional<User> findByOrganizationIdAndUsername(Long organizationId, String username);

    /** The admin user directory, scoped to the caller's own organization -- an ADMIN manages
     * their own tenant's accounts, never another tenant's. */
    List<User> findByOrganizationId(Long organizationId);

    org.springframework.data.domain.Page<User> findByOrganizationId(Long organizationId, org.springframework.data.domain.Pageable pageable);

    Optional<User> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    long countByOrganizationId(Long organizationId);

    /** Which account(s) within one tenant, if any, can see this loan via GET /my/loans --
     * usually zero or one, but not constrained to one (e.g. a refinance could in principle link
     * the same loanId to two accounts), so this returns every match rather than assuming
     * uniqueness. */
    List<User> findByOrganizationIdAndLoanIdsContaining(Long organizationId, String loanId);

    /**
     * Atomic failed-login bump: increment and lock decision happen in one UPDATE, so concurrent
     * bad attempts can never lose an update (the old load-increment-save sequence let N parallel
     * guesses register as fewer than N, quietly widening the lockout threshold). Postgres
     * serializes the row's own pre-update counter that the CASE reads.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = u.failedLoginAttempts + 1, "
            + "u.lockedUntil = case when u.failedLoginAttempts + 1 >= :maxAttempts then :lockUntil else u.lockedUntil end "
            + "where u.organization.id = :organizationId and u.username = :username")
    int recordFailedLogin(@Param("organizationId") Long organizationId, @Param("username") String username,
                          @Param("maxAttempts") int maxAttempts, @Param("lockUntil") Instant lockUntil);
}
