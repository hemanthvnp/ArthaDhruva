package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.ModelService;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registry starts against the shipped artifacts, so every checksum a card declares is verified. */
class ModelRegistryTest {

    @Test
    void startsAndListsTheTrajectoryModelFromItsCard() throws Exception {
        ModelRegistry registry = new ModelRegistry(new ModelService(new MarketData()), new SurvivalModel(true),
                new SimpleMeterRegistry());
        ModelRegistry.Entry lstm = registry.entries().stream().filter(e -> e.id().equals("trajectory_lstm")).findFirst().orElseThrow();
        assertTrue(lstm.validated());
        assertEquals(Boolean.TRUE, lstm.checksumVerified());
        assertTrue(registry.card("trajectory_lstm").isPresent());
        assertTrue((double) lstm.headline().get("outOfTimeAuc") > 0.6);
    }
}
