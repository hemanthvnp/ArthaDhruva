package com.arthadhruva.riskengine.notification;

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

/** ANALYST/ADMIN only (SecurityConfig's default access rule) -- today's only notification
 * triggers are case-assignment/note events in {@code workflow}, which are themselves
 * ANALYST/ADMIN-only, so a CLIENT never has a notification to read yet. Revisit this rule if a
 * CLIENT-facing notification trigger is ever added. */
@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    public List<NotificationView> list(@RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        return notificationService.listForUser(TenantContext.get(), authentication.getName(), limit).stream()
                .map(NotificationView::of)
                .toList();
    }

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
