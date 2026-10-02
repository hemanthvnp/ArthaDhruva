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

    /** Every state and every correlation between two of them, each edge once (smaller code first). */
    public record Graph(List<String> states, List<List<String>> edges) {
    }

    /** The whole graph in one read, for drawing it: the page used to ask each state for its neighbours. */
    public Graph graph() {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                List<String> states = new ArrayList<>();
                Result nodes = tx.run("MATCH (s:State) RETURN s.code AS code ORDER BY code");
                while (nodes.hasNext()) {
                    states.add(nodes.next().get("code").asString());
                }
                List<List<String>> edges = new ArrayList<>();
                Result pairs = tx.run("MATCH (a:State)-[:CORRELATES]-(b:State) WHERE a.code < b.code "
                        + "RETURN DISTINCT a.code AS a, b.code AS b ORDER BY a, b");
                while (pairs.hasNext()) {
                    Record record = pairs.next();
                    edges.add(List.of(record.get("a").asString(), record.get("b").asString()));
                }
                return new Graph(states, edges);
            });
        }
    }

    /**
     * Every state reachable within {@code maxHops} of {@code state}, with its shortest hop distance:
     * the Cypher equivalent of the notebook's {@code hops_away()} (which wraps
     * {@code nx.single_source_shortest_path_length}).
     *
     * <p>{@code shortestPath} runs a breadth-first search per target. The earlier form matched every
     * path of up to {@code maxHops} edges and took the minimum length, which enumerates paths: on a
     * graph this dense their number grows with the degree to the power of the hop limit.
     *
     * <p>Neo4j does not support parameterizing the bounds of a variable-length pattern
     * ({@code *..$maxHops} is a syntax error), so {@code maxHops} is inlined into the query text. That is
     * safe because it is an int range-checked by the controller (@Min(1) @Max(5)), not user text.
     */
    public List<SegmentNeighbor> neighbors(String state, int maxHops) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(
                        "MATCH (a:State {code: $source}), (b:State) WHERE b.code <> $source "
                                + "MATCH p = shortestPath((a)-[:CORRELATES*.." + maxHops + "]-(b)) "
                                + "RETURN b.code AS state, length(p) AS hops "
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
