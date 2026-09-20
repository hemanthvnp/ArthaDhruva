package com.arthadhruva.riskengine.notification;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** A user's own per-event delivery preferences (instant / daily digest / off). */
@RestController
public class NotificationPreferenceController {

    private final NotificationService service;

    public NotificationPreferenceController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/notification-preferences")
    public Map<NotificationType, DeliveryMode> get(Authentication auth) {
        return service.preferences(TenantContext.get(), auth.getName());
    }

    public record SetPreference(NotificationType type, DeliveryMode mode) {
    }

    @PutMapping("/notification-preferences")
    public ResponseEntity<?> set(@RequestBody SetPreference request, Authentication auth) {
        if (request.type() == null || request.mode() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "type and mode are required"));
        }
        try {
            service.setPreference(TenantContext.get(), auth.getName(), request.type(), request.mode());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(service.preferences(TenantContext.get(), auth.getName()));
    }
}
