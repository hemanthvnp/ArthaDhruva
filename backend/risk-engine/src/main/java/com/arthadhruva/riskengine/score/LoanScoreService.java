package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.event.LoanScoredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The {@code score} module's facade for {@link LoanScoreRecord}, the durable record of each loan's most
 * recent score and the model version that produced it.
 *
 * <p>Recording a score is part of producing it, so a failure propagates: an unrecorded credit score is
 * an audit gap, not something to swallow (the previous fail-open version returned the score while
 * silently dropping the record). The {@link LoanScoredEvent} is published inside the same transaction:
 * the webhook outbox row commits with the score, and automation rules run only after commit.
 */
@Service
public class LoanScoreService {

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

    public void upsert(Long tenantId, String loanId, double rawProbability, double calibratedProbability, Instant computedAt,
                       String modelVersion) {
        transactionTemplate.executeWithoutResult(tx -> {
            LoanScoreId id = new LoanScoreId(tenantId, loanId);
            LoanScoreRecord record = loanScoreRecordRepository.findById(id)
                    .orElseGet(() -> new LoanScoreRecord(id, rawProbability, calibratedProbability, computedAt, modelVersion));
            record.update(rawProbability, calibratedProbability, computedAt, modelVersion);
            loanScoreRecordRepository.save(record);
            eventPublisher.publishEvent(new LoanScoredEvent(tenantId, loanId, rawProbability, calibratedProbability));
        });
    }

    public Optional<LoanScoreRecord> findByTenantAndLoanId(Long tenantId, String loanId) {
        return loanScoreRecordRepository.findById(new LoanScoreId(tenantId, loanId));
    }

    /** Every loan a tenant has scored so far, most recent first. */
    public List<LoanScoreRecord> recentForTenant(Long tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
        return loanScoreRecordRepository.findAllByIdTenantIdOrderByComputedAtDesc(tenantId, PageRequest.of(0, bounded)).getContent();
    }

    /** Streams every score of the tenant in loan-id order (keyset pages), for exports of any size. */
    public void forEachScore(Long tenantId, Consumer<LoanScoreRecord> sink) {
        String after = "";
        while (true) {
            List<LoanScoreRecord> page = loanScoreRecordRepository.findByIdTenantIdAndIdLoanIdGreaterThanOrderByIdLoanIdAsc(
                    tenantId, after, PageRequest.of(0, 1000));
            page.forEach(sink);
            if (page.size() < 1000) {
                return;
            }
            after = page.get(page.size() - 1).getLoanId();
        }
    }

    /** The tenant's most recent calibrated PDs (for population-stability monitoring). */
    public List<Double> recentProbabilities(Long tenantId, int limit) {
        return loanScoreRecordRepository.recentProbabilities(tenantId, PageRequest.of(0, Math.max(1, Math.min(limit, 20_000))));
    }
}
