package com.arthadhruva.riskengine.notification;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Any signed-in ANALYST, ADMIN or CLIENT (SecurityConfig's {@code /notifications/**} rule): every call
 * reads or marks only the caller's own notifications. Staff hear about case assignments and notes; a
 * borrower hears when a document is added to their loan (NotificationEventListener). */
@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Polled by every open browser tab; a row in the audit trail per poll would drown the real entries. */
    @NotAudited
    @GetMapping("/notifications")
    public List<NotificationView> list(@RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        return notificationService.listForUser(TenantContext.get(), authentication.getName(), limit).stream()
                .map(NotificationView::of)
                .toList();
    }

    @NotAudited
    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("count", notificationService.unreadCount(TenantContext.get(), authentication.getName()));
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, String> markRead(@PathVariable Long id, Authentication authentication) {
        notificationService.markRead(TenantContext.get(), authentication.getName(), id);
        return Map.of("message", "Marked read.");
    }

    public record NotificationView(Long id, String message, String link, boolean read, Instant createdAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getMessage(), n.getLink(), n.isRead(), n.getCreatedAt());
        }
    }
}
