package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.notification.NotificationService;
import com.arthadhruva.riskengine.notification.NotificationType;
import com.arthadhruva.riskengine.workflow.LoanCase;
import com.arthadhruva.riskengine.workflow.LoanCaseService;
import org.springframework.stereotype.Component;

/** Factory: turns a stored {@link ActionSpec} into the executable command. Actions go through the
 * owning modules' service facades (workflow, notification), never their repositories. */
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
            case FLAG_CASE -> (ctx, rule) -> {
                LoanCase c = loanCaseService.getOrCreate(ctx.tenantId(), ctx.loanId());
                loanCaseService.update(ctx.tenantId(), ctx.loanId(), c.getStatus(), c.getAssignedTo(), true);
            };
            case ASSIGN_CASE -> (ctx, rule) -> {
                LoanCase c = loanCaseService.getOrCreate(ctx.tenantId(), ctx.loanId());
                loanCaseService.update(ctx.tenantId(), ctx.loanId(), c.getStatus(), spec.param(), c.isFlagged());
            };
            case SEND_NOTIFICATION -> (ctx, rule) -> notificationService.notify(ctx.tenantId(), spec.param(), NotificationType.AUTOMATION,
                    "Automation rule '" + rule + "' fired for loan " + ctx.loanId(), "/loans/" + ctx.loanId());
        };
    }
}
