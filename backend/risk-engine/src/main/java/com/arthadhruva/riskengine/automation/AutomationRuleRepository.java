package com.arthadhruva.riskengine.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {
    List<AutomationRule> findByTenantIdAndTriggerAndEnabledTrueOrderByPositionAscIdAsc(Long tenantId, RuleTrigger trigger);

    List<AutomationRule> findByTenantIdOrderByPositionAscIdAsc(Long tenantId);

    Optional<AutomationRule> findByIdAndTenantId(Long id, Long tenantId);
}
