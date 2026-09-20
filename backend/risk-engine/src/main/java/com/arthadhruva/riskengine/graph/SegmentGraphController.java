package com.arthadhruva.riskengine.graph;

import com.arthadhruva.riskengine.cache.CacheService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Serves the segment-correlation graph {@link GraphLoader} loads at startup -- the genuine
 * multi-hop traversal query that justifies a graph representation here (per
 * segment_correlation_graph.ipynb's own framing: grouping by a shared label is just a GROUP BY,
 * not a graph; this answers "which segments are N hops away, via correlated risk" instead).
 */
@RestController
@Validated
public class SegmentGraphController {

    /** The graph only changes when {@link GraphLoader} re-runs at the next deploy, so a
     * same-state-and-maxHops lookup is safe to serve stale for a full day rather than re-running
     * the Cypher traversal against Neo4j on every call. */
    private static final Duration NEIGHBORS_CACHE_TTL = Duration.ofHours(24);

    private final SegmentGraphService segmentGraphService;
    private final CacheService cacheService;

    public SegmentGraphController(SegmentGraphService segmentGraphService, CacheService cacheService) {
        this.segmentGraphService = segmentGraphService;
        this.cacheService = cacheService;
    }

    @GetMapping("/segments")
    public List<String> listStates() {
        return segmentGraphService.listStates();
    }

    @GetMapping("/segments/{state}/neighbors")
    public List<SegmentNeighbor> neighbors(
            @PathVariable String state,
            @RequestParam(defaultValue = "2") @Min(1) @Max(5) int maxHops) {
        String key = "segment-neighbors:" + state + ":" + maxHops;
        return cacheService.get(key, SegmentNeighborsResponse.class)
                .orElseGet(() -> {
                    SegmentNeighborsResponse response = new SegmentNeighborsResponse(segmentGraphService.neighbors(state, maxHops));
                    cacheService.put(key, response, NEIGHBORS_CACHE_TTL);
                    return response;
                })
                .neighbors();
    }
}
