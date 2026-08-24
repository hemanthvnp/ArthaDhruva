package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.cache.CacheService;
import jakarta.validation.Valid;
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

    private static final Duration SCORE_CACHE_TTL = Duration.ofHours(24);

    private final ModelService modelService;
    private final CacheService cacheService;

    public ScoreController(ModelService modelService, CacheService cacheService) {
        this.modelService = modelService;
        this.cacheService = cacheService;
    }

    @PostMapping("/score")
    public ScoreResponse score(@Valid @RequestBody LoanFeatures loan) {
        ScoreResponse response = modelService.score(loan);
        if (loan.loanId() != null) {
            cacheService.put(cacheKey(loan.loanId()),
                    new ScoreResponse.CachedScore(response, Instant.now()), SCORE_CACHE_TTL);
        }
        return response;
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
