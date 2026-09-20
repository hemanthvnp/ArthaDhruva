package com.arthadhruva.riskengine.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, NotificationPreference.Key> {
    List<NotificationPreference> findByIdTenantIdAndIdUsername(Long tenantId, String username);
}
