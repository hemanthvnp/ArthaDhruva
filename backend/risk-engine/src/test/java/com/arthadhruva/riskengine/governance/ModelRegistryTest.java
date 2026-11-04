package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.graph.SegmentGraphArtifact;
import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.ModelService;
import com.arthadhruva.riskengine.survival.DelinquencyModel;
import com.arthadhruva.riskengine.survival.LgdPredictor;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registry starts against the shipped artifacts, so every checksum a card declares is verified. */
class ModelRegistryTest {

    private static ModelRegistry registry(SegmentGraphArtifact graph) throws Exception {
        SurvivalModel survival = new SurvivalModel(true);
        return new ModelRegistry(new ModelService(new MarketData()), survival, new DelinquencyModel(survival), graph, new SimpleMeterRegistry());
    }

    @Test
    void listsTheDelinquencyModelAsValidatedWithItsCardAndChecksum() throws Exception {
        ModelRegistry registry = registry(new SegmentGraphArtifact());
        ModelRegistry.Entry entry = registry.entries().stream().filter(e -> e.id().equals("delinquency")).findFirst().orElseThrow();
        assertTrue(entry.validated());
        assertEquals(1, entry.tier());
        assertEquals(Boolean.TRUE, entry.checksumVerified());
        assertEquals("delinquency_model.onnx", entry.artifact());
        assertTrue(registry.card("delinquency").isPresent());
        assertTrue((double) entry.headline().get("delinquentLogLoss") < (double) entry.headline().get("survivalModelDelinquentLogLoss"));
        assertEquals(0, entry.headline().get("failedGates"));
        assertFalse(entry.gaps().isEmpty(), "what the model does not cover is listed");
    }

    @Test
    void theRegimeAndEarlyWarningModelsAreValidatedAndSayWhatTheirValidationFound() throws Exception {
        ModelRegistry registry = registry(new SegmentGraphArtifact());
        for (String id : List.of("regime_hmm", "early_warning")) {
            ModelRegistry.Entry e = registry.entries().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertTrue(e.validated(), id);
            assertEquals(Boolean.TRUE, e.checksumVerified(), id);
            assertEquals(64, e.sha256().length(), id);
            assertTrue(registry.card(id).isPresent(), id);
            assertFalse(e.gaps().isEmpty(), id + ": the validation's findings are listed, not hidden");
            assertFalse(e.headline().isEmpty(), id);
        }
        ModelRegistry.Entry regime = registry.entries().stream().filter(x -> x.id().equals("regime_hmm")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, regime.headline().get("refitReproducesShippedChain"));
        assertTrue(regime.gaps().stream().anyMatch(g -> g.contains("not stable out of time") || g.contains("labels")),
                "the regime chain's out-of-time instability is stated: " + regime.gaps());
    }

    @Test
    void lossGivenDefaultIsListedAsAConstantWithTheRetiredModelsFindings() throws Exception {
        ModelRegistry registry = registry(new SegmentGraphArtifact());
        ModelRegistry.Entry lgd = registry.entries().stream().filter(x -> x.id().equals("lgd")).findFirst().orElseThrow();
        assertFalse(lgd.validated(), "a constant is not a validated model");
        assertNull(lgd.artifact(), "no artifact is loaded for it");
        assertEquals(LgdPredictor.HISTORICAL_MEAN_LGD, (double) lgd.headline().get("fixedFloor"));
        assertTrue(lgd.gaps().stream().anyMatch(g -> g.contains("no better than it out of time")), lgd.gaps().toString());
    }

    @Test
    void aCardRefusesAnArtifactItDoesNotDescribe() {
        byte[] artifact = "{\"const\": -5.07}".getBytes(StandardCharsets.UTF_8);
        String declared = HexFormat.of().formatHex(sha256(artifact));
        ModelRegistry.verifyChecksum("survival_model.json", artifact, declared, "survival_model_card.json");   // matches: no exception
        byte[] altered = "{\"const\": -5.08}".getBytes(StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ModelRegistry.verifyChecksum("survival_model.json", altered, declared, "survival_model_card.json"));
        assertTrue(e.getMessage().contains("survival_model.json") && e.getMessage().contains("survival_model_card.json"), e.getMessage());
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
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
