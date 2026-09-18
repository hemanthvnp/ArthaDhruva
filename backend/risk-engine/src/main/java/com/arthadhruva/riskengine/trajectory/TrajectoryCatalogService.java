package com.arthadhruva.riskengine.trajectory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A browsable catalog of real loans' actual observed trajectories (exported by
 * backend/export_trajectory_catalog.py) so an analyst can look up and score one by its real
 * loan_sequence_number instead of hand-typing a month-by-month history.
 */
@Service
public class TrajectoryCatalogService {

    private final List<TrajectoryCatalogEntry> entries;

    public TrajectoryCatalogService() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("trajectory_catalog.json")) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: trajectory_catalog.json");
            }
            this.entries = mapper.readValue(is.readAllBytes(), new TypeReference<List<TrajectoryCatalogEntry>>() {});
        }
    }

    public List<TrajectoryCatalogEntry> all() {
        return entries;
    }
}
