package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.event.LoanCaseUpdatedEvent;
import com.arthadhruva.riskengine.event.LoanScoredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Observer: reacts to domain events with the tenant's matching rules -- only once the triggering change
 * has committed. Running rules inside the triggering transaction let a failing rule mark that
 * transaction rollback-only, and the swallowed error then silently rolled back the score or case change
 * itself. After commit, the trigger is final no matter what a rule does.
 */
@Component
public class AutomationEventListener {

    private final AutomationService automationService;
    private final AutomationEngine engine;

    public AutomationEventListener(AutomationService automationService, AutomationEngine engine) {
        this.automationService = automationService;
        this.engine = engine;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onScored(LoanScoredEvent event) {
        if (AutomationEngine.isRunning()) {
            return;
        }
        Map<String, Object> facts = new HashMap<>();
        facts.put("calibratedRisk", event.getCalibratedProbability());
        facts.put("rawRisk", event.getRawProbability());
        engine.run(automationService.enabledRules(event.getTenantId(), RuleTrigger.LOAN_SCORED),
                new RuleContext(event.getTenantId(), event.getLoanId(), facts));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCaseUpdated(LoanCaseUpdatedEvent event) {
        if (AutomationEngine.isRunning()) {
            return;
        }
        Map<String, Object> facts = new HashMap<>();
        facts.put("status", event.getStatus().name());
        facts.put("flagged", event.isFlagged());
        facts.put("assignedTo", event.getAssignedTo());
        engine.run(automationService.enabledRules(event.getTenantId(), RuleTrigger.LOAN_CASE_UPDATED),
                new RuleContext(event.getTenantId(), event.getLoanId(), facts));
    }
}
