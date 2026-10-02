package com.arthadhruva.riskengine.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    List<AccessRequest> findByStatusOrderByCreatedAtDesc(AccessRequest.Status status);

    List<AccessRequest> findAllByOrderByCreatedAtDesc();
}
