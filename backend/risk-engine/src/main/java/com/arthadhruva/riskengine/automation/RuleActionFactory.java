package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.notification.NotificationService;
import com.arthadhruva.riskengine.notification.NotificationType;
import com.arthadhruva.riskengine.workflow.LoanCase;
import com.arthadhruva.riskengine.workflow.LoanCaseService;
import org.springframework.stereotype.Component;

/** Factory: turns a stored {@link ActionSpec} into the executable command. Actions go through the
 * owning modules' facades and apply to the case's CURRENT state (never a stale snapshot), recorded in
 * the case history under the rule's name. */
@Component
public class RuleActionFactory {

    private final LoanCaseService loanCaseService;
    private final NotificationService notificationService;

    public RuleActionFactory(LoanCaseService loanCaseService, NotificationService notificationService) {
        this.loanCaseService = loanCaseService;
        this.notificationService = notificationService;
    }

    public RuleAction create(ActionSpec spec) {
        return switch (spec.type()) {
            case FLAG_CASE -> (ctx, rule) -> loanCaseService.update(ctx.tenantId(), ctx.loanId(), actor(rule), null,
                    "Automation rule '" + rule + "'", s -> new LoanCase.State(s.status(), s.assignedTo(), true));
            case ASSIGN_CASE -> (ctx, rule) -> loanCaseService.update(ctx.tenantId(), ctx.loanId(), actor(rule), null,
                    "Automation rule '" + rule + "'", s -> new LoanCase.State(s.status(), spec.param(), s.flagged()));
            case SEND_NOTIFICATION -> (ctx, rule) -> notificationService.notify(ctx.tenantId(), spec.param(), NotificationType.AUTOMATION,
                    "Automation rule '" + rule + "' fired for loan " + ctx.loanId(), "/loans/" + ctx.loanId());
        };
    }

    private static String actor(String rule) {
        String actor = "automation:" + rule;
        return actor.length() > 255 ? actor.substring(0, 255) : actor;
    }
}
