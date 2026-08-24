package com.arthadhruva.riskengine.trajectory;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrajectoryController {

    private final TrajectoryModelService trajectoryModelService;

    public TrajectoryController(TrajectoryModelService trajectoryModelService) {
        this.trajectoryModelService = trajectoryModelService;
    }

    @PostMapping("/trajectory-score")
    public TrajectoryScoreResponse score(@Valid @RequestBody TrajectoryRequest request) {
        return trajectoryModelService.score(request);
    }
}
