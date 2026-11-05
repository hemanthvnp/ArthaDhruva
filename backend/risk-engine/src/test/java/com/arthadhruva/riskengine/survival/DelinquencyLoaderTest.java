package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delinquency artifact is binding: the service must refuse to start when the ONNX file does not match its card, or
 * when the card's features are not the survival model's followed by the four this model adds. These tests hand the
 * loader a card altered in each way and require the refusal, so a loosened check fails the build.
 */
class DelinquencyLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode shippedCard() {
        return (ObjectNode) DelinquencyFixtures.MODEL.card().deepCopy();
    }

    private static DelinquencyModel load(ObjectNode card) throws Exception {
        return new DelinquencyModel(SurvivalFixtures.MODEL, MAPPER.writeValueAsBytes(card));
    }

    private static List<String> features(ObjectNode card) {
        List<String> out = new ArrayList<>();
        card.get("features_in_order").forEach(f -> out.add(f.asString()));
        return out;
    }

    private static void setFeatures(ObjectNode card, List<String> features) {
        ArrayNode array = MAPPER.createArrayNode();
        features.forEach(array::add);
        card.set("features_in_order", array);
    }

    @Test
    void theShippedCardLoadsAndTheChecksumIsTheArtifacts() throws Exception {
        DelinquencyModel again = load(shippedCard());
        assertEquals(DelinquencyFixtures.MODEL.artifactSha256(), again.artifactSha256());
        assertEquals(DelinquencyFixtures.MODEL.version(), again.version());
    }

    @Test
    void aCardWithAnotherChecksumRefusesToLoad() {
        ObjectNode card = shippedCard();
        card.put("artifact_sha256", "0".repeat(64));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(card));
        assertTrue(e.getMessage().contains("does not match the checksum"), e.getMessage());
    }

    @Test
    void aCardWithOneChangedHexDigitRefusesToLoad() {
        ObjectNode card = shippedCard();
        String sha = card.get("artifact_sha256").asString();
        char flipped = sha.charAt(0) == '0' ? '1' : '0';
        card.put("artifact_sha256", flipped + sha.substring(1));
        assertThrows(IllegalStateException.class, () -> load(card));
    }

    @Test
    void survivalFeaturesInAnotherOrderRefuseToLoad() {
        ObjectNode card = shippedCard();
        List<String> features = features(card);
        String first = features.get(0);
        features.set(0, features.get(1));
        features.set(1, first);
        setFeatures(card, features);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(card));
        assertTrue(e.getMessage().contains("first features must be the survival model's"), e.getMessage());
    }

    @Test
    void aCardWithoutTheHistoryFeaturesRefusesToLoad() {
        ObjectNode card = shippedCard();
        List<String> features = features(card);
        setFeatures(card, features.subList(0, features.size() - 1));
        assertThrows(IllegalStateException.class, () -> load(card));
    }
}
