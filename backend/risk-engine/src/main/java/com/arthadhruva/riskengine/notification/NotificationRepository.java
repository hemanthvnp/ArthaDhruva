package com.arthadhruva.riskengine.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Only INSTANT rows are visible: queued (digest-pending) and already-digested ones are not. */
    Page<Notification> findByTenantIdAndRecipientUsernameAndStatusOrderByCreatedAtDesc(
            Long tenantId, String recipientUsername, String status, Pageable pageable);

    long countByTenantIdAndRecipientUsernameAndStatusAndReadFalse(Long tenantId, String recipientUsername, String status);

    List<Notification> findByTenantIdAndStatusOrderByRecipientUsernameAscCreatedAtAsc(Long tenantId, String status);

    /** Scoped by tenant AND recipient, not just id -- a user can only ever look up (and later
     * mark read) their own notifications, never another user's by guessing an id. */
    Optional<Notification> findByIdAndTenantIdAndRecipientUsername(Long id, Long tenantId, String recipientUsername);
}
