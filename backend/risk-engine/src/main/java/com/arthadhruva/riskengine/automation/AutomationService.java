package com.arthadhruva.riskengine.automation;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** The {@code automation} module's facade; the repository is package-private. */
@Service
public class AutomationService {

    private final AutomationRuleRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public AutomationService(AutomationRuleRepository repository) {
        this.repository = repository;
    }

    public List<AutomationRule> enabledRules(Long tenantId, RuleTrigger trigger) {
        return repository.findByTenantIdAndTriggerAndEnabledTrueOrderByPositionAscIdAsc(tenantId, trigger);
    }

    public List<AutomationRule> list(Long tenantId) {
        return repository.findByTenantIdOrderByPositionAscIdAsc(tenantId);
    }

    /** @throws IllegalArgumentException if the field isn't testable for the trigger, a string fact is
     * given an ordering operator, a numeric value isn't numeric, or an action lacks its parameter. */
    public AutomationRule create(Long tenantId, String name, RuleTrigger trigger, String field, ConditionOp op,
                                 String value, List<ActionSpec> actions, int position) {
        if (!trigger.supportsField(field)) {
            throw new IllegalArgumentException("Field '" + field + "' is not available for trigger " + trigger);
        }
        boolean numericField = field.endsWith("Risk");
        if (numericField) {
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value for '" + field + "' must be a number");
            }
        } else if (op != ConditionOp.EQ && op != ConditionOp.NE) {
            throw new IllegalArgumentException("Only EQ/NE are valid for non-numeric field '" + field + "'");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("A rule needs at least one action");
        }
        for (ActionSpec a : actions) {
            boolean needsParam = a.type() != ActionType.FLAG_CASE;
            if (needsParam && (a.param() == null || a.param().isBlank())) {
                throw new IllegalArgumentException("Action " + a.type() + " requires a param (username)");
            }
        }
        return repository.save(new AutomationRule(tenantId, name, trigger, field, op, value,
                mapper.writeValueAsString(actions), position));
    }

    public boolean setEnabled(Long tenantId, Long id, boolean enabled) {
        return repository.findByIdAndTenantId(id, tenantId).map(rule -> {
            rule.setEnabled(enabled);
            repository.save(rule);
            return true;
        }).orElse(false);
    }

    public boolean delete(Long tenantId, Long id) {
        return repository.findByIdAndTenantId(id, tenantId).map(rule -> {
            repository.delete(rule);
            return true;
        }).orElse(false);
    }
}
