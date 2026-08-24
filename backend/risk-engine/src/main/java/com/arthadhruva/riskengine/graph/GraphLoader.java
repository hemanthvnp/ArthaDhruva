package com.arthadhruva.riskengine.graph;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads the segment-correlation graph (exported by backend/export_segment_correlation.py, which
 * recomputes segment_correlation_graph.ipynb's methodology directly from data/) into Neo4j on
 * startup. Uses MERGE for both nodes and relationships, so restarting the app never duplicates
 * the graph.
 *
 * Failures here are logged and swallowed, not rethrown: an {@link ApplicationRunner} exception
 * is fatal to {@code SpringApplication.run()} by default, which would take down PD/CVaR/LGD/LSTM
 * serving too if Neo4j merely happens to be slow or unavailable at boot -- a Neo4j outage should
 * only degrade {@code /segments}/{@code /segments/{state}/neighbors} (which will then fail
 * per-request against a driver with nothing loaded), the same fail-open philosophy already
 * applied to Postgres/Redis elsewhere in this service.
 */
@Component
public class GraphLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphLoader.class);

    private final Driver driver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraphLoader(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            loadGraph();
        } catch (Exception e) {
            log.warn("Failed to load segment-correlation graph into Neo4j -- "
                    + "/segments endpoints will not work until this succeeds on a future restart", e);
        }
    }

    private void loadGraph() throws IOException {
        GraphExport export;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("segment_correlation_graph.json")) {
            if (is == null) {
                throw new IOException("segment_correlation_graph.json not found on classpath");
            }
            export = objectMapper.readValue(is.readAllBytes(), GraphExport.class);
        }

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                for (String state : export.nodes()) {
                    tx.run("MERGE (:State {code: $code})", Map.of("code", state));
                }
                for (Edge edge : export.edges()) {
                    tx.run(
                            "MATCH (a:State {code: $source}), (b:State {code: $target}) "
                                    + "MERGE (a)-[r:CORRELATES]-(b) SET r.weight = $weight",
                            Map.of("source", edge.source(), "target", edge.target(), "weight", edge.weight()));
                }
                return null;
            });
        }
        log.info("Loaded segment-correlation graph: {} nodes, {} edges", export.nodes().size(), export.edges().size());
    }

    private record GraphExport(List<String> nodes, List<Edge> edges) {
    }

    private record Edge(String source, String target, double weight) {
    }
}
