package com.arthadhruva.riskengine.score;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScoreController {

    private final ModelService modelService;

    public ScoreController(ModelService modelService) {
        this.modelService = modelService;
    }

    @PostMapping("/score")
    public ScoreResponse score(@Valid @RequestBody LoanFeatures loan) {
        return modelService.score(loan);
    }
}
