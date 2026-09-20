package com.arthadhruva.riskengine.notification;

import com.arthadhruva.riskengine.event.LoanCaseAssignedEvent;
import com.arthadhruva.riskengine.event.LoanCaseNoteAddedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reacts to {@code workflow} domain events by creating notifications -- replaces the direct
 * {@code LoanCaseService -> NotificationService} calls this module used to receive (design
 * decision 1). Runs AFTER_COMMIT (never for a rolled-back change) and asynchronously on the
 * tenant-propagating {@code eventExecutor} (see event.AsyncConfig), so notification work adds no
 * latency to the request; fallbackExecution covers publishers with no transaction. A failure is
 * logged by the async uncaught-exception handler and affects nothing else. */
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCaseAssigned(LoanCaseAssignedEvent event) {
        notificationService.notify(event.getTenantId(), event.getNewAssignee(), NotificationType.CASE_ASSIGNED,
                "You were assigned to the case for loan " + event.getLoanId(), "/loans/" + event.getLoanId());
    }

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNoteAdded(LoanCaseNoteAddedEvent event) {
        String assignee = event.getCurrentAssignee();
        if (assignee != null && !Objects.equals(assignee, event.getAuthor())) {
            notificationService.notify(event.getTenantId(), assignee, NotificationType.NOTE_ADDED,
                    event.getAuthor() + " added a note on loan " + event.getLoanId(), "/loans/" + event.getLoanId());
        }
    }
}
