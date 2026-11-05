package com.arthadhruva.riskengine.graph;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentGraphArtifactTest {

    @Test
    void readsTheShippedGraphAndItsCard() throws Exception {
        SegmentGraphArtifact artifact = new SegmentGraphArtifact();
        SegmentGraphArtifact.Graph g = artifact.graph();
        assertEquals(51, g.states().size());
        assertEquals(artifact.declaredSha256(), artifact.sha256(), "the shipped file matches its card");
        assertFalse(g.movesWith().isEmpty());
        for (SegmentGraphArtifact.Link link : g.movesWith()) {
            assertTrue(link.a().compareTo(link.b()) < 0, "each pair once, smaller code first");
            assertTrue(Math.abs(link.weight()) <= 1);
            assertTrue(link.confidence() >= 0.8 && link.confidence() <= 1);
        }
        assertTrue(g.leads().isEmpty(), "no leads link passed validation in this release");
        Map<String, Object> headline = artifact.validationHeadline();
        assertEquals(Boolean.FALSE, headline.get("leadsShipped"));
        assertTrue(((String) headline.get("leadsNote")).startsWith("Leads links are omitted"));
        assertNotNull(headline.get("linkedMinusUnlinked"));
    }

    @Test
    void readsAFileWithLeadsAndWithoutRegions() throws Exception {
        String file = """
                {"schemaVersion": 1, "version": "2027.01.31-0000", "asOf": "2026-12", "method": "m",
                 "learnedOn": {"from": "1995-02", "to": "2026-12", "sources": ["hpi"]},
                 "states": [{"code": "FL", "name": "Florida", "region": null, "covered": true, "leads": 1, "follows": 0},
                            {"code": "GA", "name": "Georgia", "region": null, "covered": true, "leads": 0, "follows": 1}],
                 "movesWith": [{"a": "FL", "b": "GA", "weight": 0.3, "sign": 1, "confidence": 0.9, "basis": ["hpi"]}],
                 "leads": [{"from": "FL", "to": "GA", "lagMonths": 3, "weight": 0.2, "sign": 1, "confidence": 0.85, "basis": ["hpi"]}],
                 "regionalShare": {"estimate": 0.0, "interval": [0.0, 0.01], "default": 0.0}}
                """;
        SegmentGraphArtifact artifact = new SegmentGraphArtifact(file.getBytes(StandardCharsets.UTF_8), resource(SegmentGraphArtifact.CARD));
        assertTrue(artifact.graph().regions().isEmpty());
        assertEquals(3, artifact.graph().leads().get(0).lagMonths());
        assertNull(artifact.graph().states().get(0).region());
        assertEquals(Boolean.TRUE, artifact.validationHeadline().get("leadsShipped"));
        assertNull(artifact.validationHeadline().get("leadsNote"));
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = SegmentGraphArtifactTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }
}
