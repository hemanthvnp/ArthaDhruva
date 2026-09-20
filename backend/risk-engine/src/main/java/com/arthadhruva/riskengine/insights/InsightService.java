package com.arthadhruva.riskengine.insights;

import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.workflow.LoanCaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes and stores per-tenant ML insights. Callers set the tenant context (row-level security). */
@Service
public class InsightService {

    public static final String NOTE_TOPICS = "NOTE_TOPICS";
    public static final String BORROWER_SEGMENTS = "BORROWER_SEGMENTS";
    private static final long SEED = 42L;
    private static final int MAX_NOTES = 2000;

    private final JdbcTemplate jdbc;
    private final LoanCaseService cases;
    private final LoanCatalogService catalog;
    private final ObjectMapper mapper = new ObjectMapper();

    public InsightService(JdbcTemplate jdbc, LoanCaseService cases, LoanCatalogService catalog) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.catalog = catalog;
    }

    /** Recomputes and stores every insight for one tenant. */
    public void refresh(Long tenantId) {
        List<String> notes = cases.noteTextsForTenant(tenantId, MAX_NOTES);
        store(tenantId, NOTE_TOPICS, Map.of("noteCount", notes.size(), "topics", TopicModel.topics(notes, 5, SEED)));
        var loans = catalog.all();
        store(tenantId, BORROWER_SEGMENTS, Map.of("loanCount", loans.size(), "segments", BorrowerSegmenter.segment(loans, 4, SEED)));
    }

    /** The stored snapshot, computing it first if this tenant has none yet. */
    public Map<String, Object> latest(Long tenantId, String kind) {
        Map<String, Object> snapshot = read(tenantId, kind);
        if (snapshot == null) {
            refresh(tenantId);
            snapshot = read(tenantId, kind);
        }
        return snapshot;
    }

    private void store(Long tenantId, String kind, Object payload) {
        jdbc.update("INSERT INTO insight_snapshot (tenant_id, kind, payload) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, kind) DO UPDATE SET payload = EXCLUDED.payload, computed_at = now()",
                tenantId, kind, mapper.writeValueAsString(payload));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(Long tenantId, String kind) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT computed_at, payload FROM insight_snapshot WHERE tenant_id = ? AND kind = ?", tenantId, kind);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("computedAt", ((java.sql.Timestamp) rows.get(0).get("computed_at")).toInstant().toString());
        out.putAll(mapper.readValue((String) rows.get(0).get("payload"), Map.class));
        return out;
    }
}
