package com.arthadhruva.riskengine.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SegmentGraphService {

    private final Driver driver;

    public SegmentGraphService(Driver driver) {
        this.driver = driver;
    }

    public List<String> listStates() {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run("MATCH (s:State) RETURN s.code AS code ORDER BY code");
                List<String> states = new ArrayList<>();
                while (result.hasNext()) {
                    states.add(result.next().get("code").asString());
                }
                return states;
            });
        }
    }

    /**
     * Fixed-hop-count traversal, the direct Cypher equivalent of the notebook's
     * {@code hops_away()} (which wraps {@code nx.single_source_shortest_path_length}): every
     * state reachable within {@code maxHops} of {@code state}, with its shortest hop distance.
     *
     * Neo4j does not support parameterizing the range bounds of a variable-length relationship
     * pattern (e.g. {@code *1..$maxHops} is a syntax error) -- {@code maxHops} is inlined into
     * the query text instead. This is safe here because it's a plain int already range-checked
     * by the controller (@Min(1) @Max(5)), not user-supplied query text.
     */
    public List<SegmentNeighbor> neighbors(String state, int maxHops) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(
                        "MATCH p=(a:State {code: $source})-[:CORRELATES*1.." + maxHops + "]-(b:State) "
                                + "WHERE b.code <> $source "
                                + "RETURN b.code AS state, min(length(p)) AS hops "
                                + "ORDER BY hops, state",
                        Map.of("source", state));
                List<SegmentNeighbor> neighbors = new ArrayList<>();
                while (result.hasNext()) {
                    Record record = result.next();
                    neighbors.add(new SegmentNeighbor(record.get("state").asString(), record.get("hops").asInt()));
                }
                return neighbors;
            });
        }
    }
}
