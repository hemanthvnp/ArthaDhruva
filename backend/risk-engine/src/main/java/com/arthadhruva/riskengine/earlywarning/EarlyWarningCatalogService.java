package com.arthadhruva.riskengine.earlywarning;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A browsable catalog of real currently-current-loan snapshots (exported by
 * backend/export_early_warning_catalog.py) so an analyst can look up and score one by an actual
 * loan/month label instead of hand-typing 31 feature values -- same reasoning as
 * {@link com.arthadhruva.riskengine.score.LoanCatalogService} for the PD model.
 */
@Service
public class EarlyWarningCatalogService {

    private final List<EarlyWarningCatalogEntry> entries;

    public EarlyWarningCatalogService() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("early_warning_catalog.json")) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: early_warning_catalog.json");
            }
            this.entries = mapper.readValue(is.readAllBytes(), new TypeReference<List<EarlyWarningCatalogEntry>>() {});
        }
    }

    public List<EarlyWarningCatalogEntry> all() {
        return entries;
    }
}
