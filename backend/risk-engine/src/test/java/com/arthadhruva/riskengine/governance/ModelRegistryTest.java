package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.graph.SegmentGraphArtifact;
import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.ModelService;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registry starts against the shipped artifacts, so every checksum a card declares is verified. */
class ModelRegistryTest {

    private static ModelRegistry registry(SegmentGraphArtifact graph) throws Exception {
        return new ModelRegistry(new ModelService(new MarketData()), new SurvivalModel(true), graph, new SimpleMeterRegistry());
    }

    @Test
    void startsAndListsTheTrajectoryModelFromItsCard() throws Exception {
        ModelRegistry registry = registry(new SegmentGraphArtifact());
        ModelRegistry.Entry lstm = registry.entries().stream().filter(e -> e.id().equals("trajectory_lstm")).findFirst().orElseThrow();
        assertTrue(lstm.validated());
        assertEquals(Boolean.TRUE, lstm.checksumVerified());
        assertTrue(registry.card("trajectory_lstm").isPresent());
        assertTrue((double) lstm.headline().get("outOfTimeAuc") > 0.6);
    }

    @Test
    void listsTheSegmentGraphAsAValidatedTierTwoModel() throws Exception {
        ModelRegistry.Entry graph = registry(new SegmentGraphArtifact()).entries().stream()
                .filter(e -> e.id().equals("segment_graph")).findFirst().orElseThrow();
        assertTrue(graph.validated());
        assertEquals(2, graph.tier());
        assertEquals(Boolean.TRUE, graph.checksumVerified());
        assertEquals(SegmentGraphArtifact.FILE, graph.artifact());
        assertTrue((double) graph.headline().get("pAgainstRandomGraphs") < 0.05);
        // No bootstrap resample at or below zero is an upper bound, shown as one.
        assertEquals("< 0.0005", graph.headline().get("pAgainstSameDivision"));
        assertTrue(graph.gaps().stream().allMatch(g -> g.contains("omitted") || g.contains("absent")),
                "what is missing, not the general limitations");
    }

    @Test
    void refusesToStartWhenTheGraphDoesNotMatchItsCard() throws Exception {
        byte[] file = resource(SegmentGraphArtifact.FILE);
        String changed = new String(file, StandardCharsets.UTF_8).replaceFirst("\"weight\": 0\\.", "\"weight\": 0.0");
        SegmentGraphArtifact tampered = new SegmentGraphArtifact(changed.getBytes(StandardCharsets.UTF_8),
                resource(SegmentGraphArtifact.CARD));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry(tampered));
        assertTrue(e.getMessage().contains(SegmentGraphArtifact.FILE) && e.getMessage().contains(SegmentGraphArtifact.CARD));
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = ModelRegistryTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }
}
