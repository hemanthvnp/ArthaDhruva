package com.arthadhruva.riskengine.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The {@code notification} module's facade -- the repositories are private to this package. */
@Service
public class NotificationService {

    private static final int MAX_LIMIT = 200;

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final TransactionTemplate tx;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationPreferenceRepository preferenceRepository,
                               TransactionTemplate tx) {
        this.tx = tx;
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
    }

    /** Honors the recipient's per-type preference: OFF creates nothing, DIGEST queues the row for
     * their next digest, INSTANT (the default) makes it visible immediately. */
    public void notify(Long tenantId, String recipientUsername, NotificationType type, String message, String link) {
        DeliveryMode mode = type == NotificationType.DIGEST_SUMMARY
                ? DeliveryMode.INSTANT : modeFor(tenantId, recipientUsername, type);
        if (mode == DeliveryMode.OFF) {
            return;
        }
        notificationRepository.save(new Notification(tenantId, recipientUsername, type, message, link,
                mode == DeliveryMode.DIGEST));
    }

    private DeliveryMode modeFor(Long tenantId, String username, NotificationType type) {
        return preferenceRepository.findById(new NotificationPreference.Key(tenantId, username, type))
                .map(NotificationPreference::getMode).orElse(DeliveryMode.INSTANT);
    }

    public Map<NotificationType, DeliveryMode> preferences(Long tenantId, String username) {
        Map<NotificationType, DeliveryMode> result = new EnumMap<>(NotificationType.class);
        for (NotificationType t : NotificationType.values()) {
            if (t != NotificationType.DIGEST_SUMMARY) {
                result.put(t, DeliveryMode.INSTANT);
            }
        }
        preferenceRepository.findByIdTenantIdAndIdUsername(tenantId, username)
                .forEach(p -> result.put(p.getType(), p.getMode()));
        return result;
    }

    public void setPreference(Long tenantId, String username, NotificationType type, DeliveryMode mode) {
        if (type == NotificationType.DIGEST_SUMMARY) {
            throw new IllegalArgumentException("Digest summaries cannot be configured");
        }
        preferenceRepository.save(new NotificationPreference(tenantId, username, type, mode));
    }

    public List<Notification> listForUser(Long tenantId, String username, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        Page<Notification> page = notificationRepository.findByTenantIdAndRecipientUsernameAndStatusOrderByCreatedAtDesc(
                tenantId, username, "INSTANT", PageRequest.of(0, bounded));
        return page.getContent();
    }

    public long unreadCount(Long tenantId, String username) {
        return notificationRepository.countByTenantIdAndRecipientUsernameAndStatusAndReadFalse(tenantId, username, "INSTANT");
    }

    /** No-op (not an error) if the notification doesn't exist or belongs to someone else --
     * "mark read" is idempotent by nature, and a user has no way to distinguish "already read"
     * from "not yours" without this leaking whether a given id exists at all. */
    public void markRead(Long tenantId, String username, Long notificationId) {
        notificationRepository.findByIdAndTenantIdAndRecipientUsername(notificationId, tenantId, username)
                .ifPresent(notification -> {
                    notification.markRead();
                    notificationRepository.save(notification);
                });
    }

    /**
     * Folds one tenant's queued notifications into a single summary per user. The caller sets the
     * tenant context (row-level security). Returns how many digests were produced.
     */
    public int deliverDigests(Long tenantId) {
        List<Notification> queued = notificationRepository.findByTenantIdAndStatusOrderByRecipientUsernameAscCreatedAtAsc(tenantId, "QUEUED");
        int digests = 0;
        List<Notification> group = new ArrayList<>();
        String current = null;
        for (Notification n : queued) {
            if (current != null && !current.equals(n.getRecipientUsername())) {
                flush(tenantId, current, group);
                digests++;
                group = new ArrayList<>();
            }
            current = n.getRecipientUsername();
            group.add(n);
        }
        if (!group.isEmpty()) {
            flush(tenantId, current, group);
            digests++;
        }
        return digests;
    }

    private void flush(Long tenantId, String username, List<Notification> group) {
        // One transaction per user: the summary and the "digested" marks commit together, so a crash
        // can never leave a summary behind while its items stay queued (which would digest them twice).
        tx.executeWithoutResult(status -> doFlush(tenantId, username, group));
    }

    private void doFlush(Long tenantId, String username, List<Notification> group) {
        StringBuilder text = new StringBuilder("Digest: " + group.size() + " update" + (group.size() == 1 ? "" : "s") + ": ");
        for (int i = 0; i < group.size() && i < 3; i++) {
            text.append(i > 0 ? "; " : "").append(group.get(i).getMessage());
        }
        if (group.size() > 3) {
            text.append("; and ").append(group.size() - 3).append(" more");
        }
        String message = text.length() > 490 ? text.substring(0, 490) + "..." : text.toString();
        notificationRepository.save(new Notification(tenantId, username, NotificationType.DIGEST_SUMMARY, message, null, false));
        for (Notification n : group) {
            n.markDigested();
        }
        notificationRepository.saveAll(group);
    }
}
