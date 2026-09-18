package com.arthadhruva.riskengine.earlywarning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EarlyWarningCatalogServiceTest {

    @Test
    void loadsRealCatalogWithBothOutcomes() throws Exception {
        EarlyWarningCatalogService service = new EarlyWarningCatalogService();
        List<EarlyWarningCatalogEntry> all = service.all();

        assertEquals(300, all.size());
        long positives = all.stream().filter(EarlyWarningCatalogEntry::actuallyWentDelinquent).count();
        assertEquals(150, positives, "should have a real mix of both outcomes for demo purposes");

        EarlyWarningCatalogEntry first = all.get(0);
        assertNotNull(first.label());
        assertNotNull(first.features());
        assertNotNull(first.features().hmmRegime());

        // Every entry must actually be scoreable end to end through the real model service.
        EarlyWarningModelService modelService = new EarlyWarningModelService();
        for (EarlyWarningCatalogEntry entry : all) {
            EarlyWarningResponse response = modelService.score(entry.features());
            assertTrue(response.calibratedRisk() >= 0 && response.calibratedRisk() <= 1);
        }
    }
}
