package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.event.LoanCaseUpdatedEvent;
import com.arthadhruva.riskengine.event.LoanScoredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Observer: reacts to domain events with the tenant's matching rules. Failures are contained by
 * the chain and by the event multicaster's error handler. */
@Component
public class AutomationEventListener {

    private final AutomationService automationService;
    private final AutomationEngine engine;

    public AutomationEventListener(AutomationService automationService, AutomationEngine engine) {
        this.automationService = automationService;
        this.engine = engine;
    }

    @EventListener
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

    @EventListener
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
