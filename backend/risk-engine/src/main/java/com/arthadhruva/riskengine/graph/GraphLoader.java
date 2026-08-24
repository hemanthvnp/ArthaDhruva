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
    public void run(ApplicationArguments args) throws IOException {
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
