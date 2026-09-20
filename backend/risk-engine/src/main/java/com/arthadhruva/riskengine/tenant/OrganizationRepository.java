package com.arthadhruva.riskengine.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    /** The login-time lookup -- an inactive (suspended) org must resolve the same as a
     * nonexistent one, never revealing which case it is (see AuthController). */
    Optional<Organization> findBySlugAndActiveTrue(String slug);

    boolean existsBySlug(String slug);
}
