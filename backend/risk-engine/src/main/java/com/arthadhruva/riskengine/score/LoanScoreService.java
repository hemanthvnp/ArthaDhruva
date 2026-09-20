package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.event.LoanScoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code score} module's façade for {@link LoanScoreRecord} -- {@link
 * LoanScoreRecordRepository} is private to this package; {@code MyLoansController} (same module,
 * but kept layered like everything else) and any future cross-module caller go through here.
 */
@Service
public class LoanScoreService {

    private static final Logger log = LoggerFactory.getLogger(LoanScoreService.class);
    private static final int MAX_RECENT_LIMIT = 500;

    private final LoanScoreRecordRepository loanScoreRecordRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public LoanScoreService(LoanScoreRecordRepository loanScoreRecordRepository, ApplicationEventPublisher eventPublisher,
                            TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.loanScoreRecordRepository = loanScoreRecordRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Fails open (log and swallow): a Postgres hiccup here must not break the actual scoring
     * response, same fail-open philosophy as everything else in this app that touches Postgres.
     * The {@link LoanScoredEvent} is published only on a successful write, since listeners
     * (future automation rules, usage metering) act on a score that is now durably recorded. */
    public void upsert(Long tenantId, String loanId, double rawProbability, double calibratedProbability, Instant computedAt) {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
            LoanScoreId id = new LoanScoreId(tenantId, loanId);
            LoanScoreRecord record = loanScoreRecordRepository.findById(id)
                    .orElseGet(() -> new LoanScoreRecord(id, rawProbability, calibratedProbability, computedAt));
            record.update(rawProbability, calibratedProbability, computedAt);
            loanScoreRecordRepository.save(record);
            eventPublisher.publishEvent(new LoanScoredEvent(tenantId, loanId, rawProbability, calibratedProbability));
            });
        } catch (Exception e) {
            log.warn("Failed to persist durable loan score record for {}", loanId, e);
        }
    }

    public Optional<LoanScoreRecord> findByTenantAndLoanId(Long tenantId, String loanId) {
        return loanScoreRecordRepository.findById(new LoanScoreId(tenantId, loanId));
    }

    /** Every loan a tenant has scored so far, most recent first. */
    public List<LoanScoreRecord> recentForTenant(Long tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
        return loanScoreRecordRepository
                .findAllByIdTenantIdOrderByComputedAtDesc(tenantId, PageRequest.of(0, bounded))
                .getContent();
    }
}
