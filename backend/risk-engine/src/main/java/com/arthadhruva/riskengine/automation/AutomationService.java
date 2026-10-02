package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.security.UserService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** The {@code automation} module's facade; the repository is package-private. */
@Service
public class AutomationService {

    /** Every enabled rule is evaluated on every matching event, so the count per tenant is bounded. */
    static final int MAX_RULES_PER_TENANT = 50;
    static final int MAX_ACTIONS_PER_RULE = 5;

    private final AutomationRuleRepository repository;
    private final UserService users;
    private final ObjectMapper mapper = new ObjectMapper();

    public AutomationService(AutomationRuleRepository repository, UserService users) {
        this.repository = repository;
        this.users = users;
    }

    public List<AutomationRule> enabledRules(Long tenantId, RuleTrigger trigger) {
        return repository.findByTenantIdAndTriggerAndEnabledTrueOrderByPositionAscIdAsc(tenantId, trigger);
    }

    public List<AutomationRule> list(Long tenantId) {
        return repository.findByTenantIdOrderByPositionAscIdAsc(tenantId);
    }

    /** @throws IllegalArgumentException if the field isn't testable for the trigger, a string fact is given an
     * ordering operator, a numeric value isn't numeric, an action lacks its parameter, the parameter is not an
     * active analyst/admin of this organization, or the organization is at its rule limit. */
    public AutomationRule create(Long tenantId, String name, RuleTrigger trigger, String field, ConditionOp op,
                                 String value, List<ActionSpec> actions, int position) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("A rule needs a name of at most 100 characters");
        }
        if (value == null || value.length() > 100) {
            throw new IllegalArgumentException("A condition value of at most 100 characters is required");
        }
        if (repository.findByTenantIdOrderByPositionAscIdAsc(tenantId).size() >= MAX_RULES_PER_TENANT) {
            throw new IllegalArgumentException("At most " + MAX_RULES_PER_TENANT + " automation rules per organization");
        }
        if (!trigger.supportsField(field)) {
            throw new IllegalArgumentException("Field '" + field + "' is not available for trigger " + trigger);
        }
        boolean numericField = field.endsWith("Risk");
        if (numericField) {
            try {
                double v = Double.parseDouble(value);
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value for '" + field + "' must be a number");
            }
        } else if (op != ConditionOp.EQ && op != ConditionOp.NE) {
            throw new IllegalArgumentException("Only EQ/NE are valid for non-numeric field '" + field + "'");
        }
        if (actions == null || actions.isEmpty() || actions.size() > MAX_ACTIONS_PER_RULE) {
            throw new IllegalArgumentException("A rule needs between 1 and " + MAX_ACTIONS_PER_RULE + " actions");
        }
        for (ActionSpec a : actions) {
            if (a.type() == null) {
                throw new IllegalArgumentException("Every action needs a type");
            }
            boolean needsParam = a.type() != ActionType.FLAG_CASE;
            if (needsParam && (a.param() == null || a.param().isBlank())) {
                throw new IllegalArgumentException("Action " + a.type() + " requires a param (username)");
            }
            if (needsParam && users.findActiveStaff(tenantId, a.param().trim()).isEmpty()) {
                throw new IllegalArgumentException("'" + a.param() + "' is not an active analyst or admin in your organization");
            }
        }
        return repository.save(new AutomationRule(tenantId, name.trim(), trigger, field, op, value.trim(),
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
