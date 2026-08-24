package com.arthadhruva.riskengine.graph;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final SegmentGraphService segmentGraphService;

    public SegmentGraphController(SegmentGraphService segmentGraphService) {
        this.segmentGraphService = segmentGraphService;
    }

    @GetMapping("/segments")
    public List<String> listStates() {
        return segmentGraphService.listStates();
    }

    @GetMapping("/segments/{state}/neighbors")
    public List<SegmentNeighbor> neighbors(
            @PathVariable String state,
            @RequestParam(defaultValue = "2") @Min(1) @Max(5) int maxHops) {
        return segmentGraphService.neighbors(state, maxHops);
    }
}
