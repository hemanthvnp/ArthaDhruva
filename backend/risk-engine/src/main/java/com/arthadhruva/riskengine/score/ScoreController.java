package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.cache.CacheService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
public class ScoreController {

    private static final Logger log = LoggerFactory.getLogger(ScoreController.class);
    private static final Duration SCORE_CACHE_TTL = Duration.ofHours(24);

    private final ModelService modelService;
    private final CacheService cacheService;
    private final LoanScoreRecordRepository loanScoreRecordRepository;

    public ScoreController(ModelService modelService, CacheService cacheService,
                            LoanScoreRecordRepository loanScoreRecordRepository) {
        this.modelService = modelService;
        this.cacheService = cacheService;
        this.loanScoreRecordRepository = loanScoreRecordRepository;
    }

    @PostMapping("/score")
    public ScoreResponse score(@Valid @RequestBody LoanFeatures loan) {
        ScoreResponse response = modelService.score(loan);
        if (loan.loanId() != null) {
            Instant now = Instant.now();
            cacheService.put(cacheKey(loan.loanId()),
                    new ScoreResponse.CachedScore(response, now), SCORE_CACHE_TTL);
            persistDurableRecord(loan.loanId(), response, now);
        }
        return response;
    }

    /**
     * Durable counterpart to the Redis cache above -- backs GET /my/loans, which a client might
     * check weeks after scoring, well past the 24h Redis TTL. Fails open (log and swallow): a
     * Postgres hiccup here must not break the actual scoring response, same fail-open philosophy
     * as everything else in this app that touches Postgres.
     */
    private void persistDurableRecord(String loanId, ScoreResponse response, Instant computedAt) {
        try {
            LoanScoreRecord record = loanScoreRecordRepository.findById(loanId)
                    .orElseGet(() -> new LoanScoreRecord(loanId, response.rawProbability(), response.calibratedProbability(), computedAt));
            record.update(response.rawProbability(), response.calibratedProbability(), computedAt);
            loanScoreRecordRepository.save(record);
        } catch (Exception e) {
            log.warn("Failed to persist durable loan score record for {}", loanId, e);
        }
    }

    /**
     * Reads a previously computed score straight from the cache -- the "Online Feature Store"
     * read path. Only returns what {@code POST /score} already cached; never recomputes.
     */
    @GetMapping("/score/{loanId}")
    public ResponseEntity<ScoreResponse.CachedScore> getCachedScore(@PathVariable String loanId) {
        return cacheService.get(cacheKey(loanId), ScoreResponse.CachedScore.class)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String cacheKey(String loanId) {
        return "score:" + loanId;
    }
}
