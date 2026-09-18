package com.arthadhruva.riskengine.trajectory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryCatalogServiceTest {

    @Test
    void loadsRealCatalogAndEveryEntryScoresEndToEnd() throws Exception {
        TrajectoryCatalogService service = new TrajectoryCatalogService();
        List<TrajectoryCatalogEntry> all = service.all();

        assertEquals(120, all.size());
        for (TrajectoryCatalogEntry entry : all) {
            assertNotNull(entry.label());
            assertTrue(entry.request().months().size() >= 3, "catalog entries should have real multi-month history");
            assertTrue(entry.request().months().size() <= 12);
        }

        TrajectoryModelService modelService = new TrajectoryModelService();
        for (TrajectoryCatalogEntry entry : all) {
            TrajectoryScoreResponse response = modelService.score(entry.request());
            assertTrue(response.probability() >= 0 && response.probability() <= 1);
        }
    }
}
