package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.event.LoanCaseAssignedEvent;
import com.arthadhruva.riskengine.event.LoanCaseUpdatedEvent;
import com.arthadhruva.riskengine.event.LoanScoredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox, write side. Runs BEFORE_COMMIT inside the same database transaction as the
 * state change that raised the event, so an outbox row exists if and only if that change
 * committed: no "state changed but the webhook was lost" (crash after commit) and no "webhook sent
 * for a change that rolled back". Delivery happens later, elsewhere ({@link WebhookDeliveryWorker}).
 * An exception here propagates and rolls the transaction back, which is the intended atomicity.
 */
@Component
class WebhookOutboxWriter {

    private final WebhookService service;
    private final ObjectMapper mapper = new ObjectMapper();

    WebhookOutboxWriter(WebhookService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onScored(LoanScoredEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loanId", e.getLoanId());
        data.put("rawProbability", e.getRawProbability());
        data.put("calibratedProbability", e.getCalibratedProbability());
        enqueue(e.getTenantId(), WebhookEventType.LOAN_SCORED, data);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onCaseUpdated(LoanCaseUpdatedEvent e) {
        if (e.isFlagged() && !e.wasFlagged()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("loanId", e.getLoanId());
            data.put("status", e.getStatus().name());
            data.put("assignedTo", e.getAssignedTo());
            enqueue(e.getTenantId(), WebhookEventType.CASE_FLAGGED, data);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onAssigned(LoanCaseAssignedEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loanId", e.getLoanId());
        data.put("assignedTo", e.getNewAssignee());
        data.put("previousAssignee", e.getPreviousAssignee());
        enqueue(e.getTenantId(), WebhookEventType.CASE_ASSIGNED, data);
    }

    private void enqueue(Long tenantId, WebhookEventType type, Map<String, Object> data) {
        for (WebhookSubscription sub : service.activeSubscriptions(tenantId)) {
            if (!sub.subscribesTo(type)) {
                continue;
            }
            UUID eventId = UUID.randomUUID();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", eventId.toString());
            envelope.put("type", type.name());
            envelope.put("occurredAt", Instant.now().toString());
            envelope.put("data", data);
            service.enqueue(new WebhookOutbox(eventId, tenantId, sub.getId(), type, mapper.writeValueAsString(envelope)));
        }
    }
}
