package com.arthadhruva.riskengine.insights;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InsightController {

    private final InsightService service;

    public InsightController(InsightService service) {
        this.service = service;
    }

    /** Topics over this organization's case notes. {@code refresh=true} recomputes now instead of
     * serving the last scheduled result. */
    @GetMapping("/insights/note-topics")
    public Map<String, Object> noteTopics(@RequestParam(defaultValue = "false") boolean refresh) {
        return get(InsightService.NOTE_TOPICS, refresh);
    }

    @GetMapping("/insights/borrower-segments")
    public Map<String, Object> borrowerSegments(@RequestParam(defaultValue = "false") boolean refresh) {
        return get(InsightService.BORROWER_SEGMENTS, refresh);
    }

    private Map<String, Object> get(String kind, boolean refresh) {
        Long tenant = TenantContext.get();
        if (refresh) {
            service.refresh(tenant);
        }
        return service.latest(tenant, kind);
    }
}
