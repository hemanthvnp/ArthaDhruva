package com.arthadhruva.riskengine.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {
    List<WebhookSubscription> findByTenantIdAndEnabledTrue(Long tenantId);

    List<WebhookSubscription> findByTenantIdOrderByIdAsc(Long tenantId);

    Optional<WebhookSubscription> findByIdAndTenantId(Long id, Long tenantId);
}

interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long> {
    Page<WebhookOutbox> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);

    Page<WebhookOutbox> findByTenantIdAndStatusOrderByIdDesc(Long tenantId, String status, Pageable pageable);
}
