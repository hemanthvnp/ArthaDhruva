package com.arthadhruva.riskengine.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /** Which account(s), if any, can see this loan via GET /my/loans -- usually zero or one, but
     * not constrained to one (e.g. a refinance could in principle link the same loanId to two
     * accounts), so this returns every match rather than assuming uniqueness. */
    List<User> findByLoanIdsContaining(String loanId);
}
