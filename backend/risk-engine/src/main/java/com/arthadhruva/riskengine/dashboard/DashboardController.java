package com.arthadhruva.riskengine.dashboard;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A user's own dashboard layout (which widgets, in what order), stored per (tenant, username) so
 * one person's arrangement never affects a colleague's. The widget ids are a fixed vocabulary the
 * frontend knows how to render; unknown ids are rejected rather than stored. Saves are write-behind
 * (see {@link DashboardConfigStore}).
 */
@RestController
public class DashboardController {

    static final List<String> AVAILABLE = List.of("PORTFOLIO_KPI", "MY_OPEN_CASES", "FLAGGED_CASES", "RECENT_NOTES", "RECENT_SCORES");
    private static final List<String> DEFAULT = List.of("PORTFOLIO_KPI", "MY_OPEN_CASES", "RECENT_NOTES");

    private final DashboardConfigStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashboardController(DashboardConfigStore store) {
        this.store = store;
    }

    public record Config(List<String> widgets) {
    }

    @GetMapping("/dashboard/config")
    public Map<String, Object> get(Authentication auth) {
        List<String> stored = store.find(TenantContext.get(), auth.getName())
                .map(json -> mapper.readValue(json, new TypeReference<List<String>>() {})).orElse(DEFAULT);
        return Map.of("widgets", stored, "available", AVAILABLE);
    }

    @PutMapping("/dashboard/config")
    public ResponseEntity<?> put(@RequestBody Config config, Authentication auth) {
        if (config == null || config.widgets() == null || config.widgets().size() > AVAILABLE.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "widgets must be a list of at most " + AVAILABLE.size() + " ids"));
        }
        Set<String> unique = new LinkedHashSet<>(config.widgets());
        if (!AVAILABLE.containsAll(unique)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown widget id", "available", AVAILABLE));
        }
        store.save(TenantContext.get(), auth.getName(), mapper.writeValueAsString(List.copyOf(unique)));
        return ResponseEntity.ok(Map.of("widgets", List.copyOf(unique), "available", AVAILABLE));
    }
}
