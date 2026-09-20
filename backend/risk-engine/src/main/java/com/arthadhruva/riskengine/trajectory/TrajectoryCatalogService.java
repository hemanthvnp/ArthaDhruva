package com.arthadhruva.riskengine.trajectory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A browsable catalog of real loans' actual observed trajectories (exported by
 * backend/export_trajectory_catalog.py) so an analyst can look up and score one by its real
 * loan_sequence_number instead of hand-typing a month-by-month history. Map-backed for O(1)
 * lookup, same reasoning as {@link com.arthadhruva.riskengine.score.LoanCatalogService}.
 */
@Service
public class TrajectoryCatalogService {

    private final Map<String, TrajectoryCatalogEntry> entriesByLabel;

    public TrajectoryCatalogService() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<TrajectoryCatalogEntry> entries;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("trajectory_catalog.json")) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: trajectory_catalog.json");
            }
            entries = mapper.readValue(is.readAllBytes(), new TypeReference<List<TrajectoryCatalogEntry>>() {});
        }
        this.entriesByLabel = entries.stream().collect(Collectors.toMap(TrajectoryCatalogEntry::label, e -> e));
    }

    public List<TrajectoryCatalogEntry> all() {
        return List.copyOf(entriesByLabel.values());
    }

    public TrajectoryCatalogEntry find(String label) {
        return entriesByLabel.get(label);
    }
}
