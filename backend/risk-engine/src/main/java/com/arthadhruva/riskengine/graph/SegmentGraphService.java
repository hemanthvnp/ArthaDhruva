package com.arthadhruva.riskengine.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SegmentGraphService {

    private final Driver driver;
    private final SegmentGraphArtifact artifact;

    public SegmentGraphService(Driver driver, SegmentGraphArtifact artifact) {
        this.driver = driver;
        this.artifact = artifact;
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

    /** A state as the page draws it; {@code region} is null for a state in no region. */
    public record StateView(String code, String name, String region) {
    }

    /** A moves-with link, each pair once with the smaller code first. */
    public record LinkView(String a, String b, double weight, Double confidence, List<String> basis) {
    }

    /**
     * The whole graph in one read: states and links from Neo4j, regions and the validation figures from the
     * artifact's card (they belong to the artifact, and there is nothing to traverse in them).
     */
    public record Graph(String version, String asOf, List<StateView> states, List<LinkView> links,
                        List<SegmentGraphArtifact.Region> regions, Map<String, Object> validation) {
    }

    public Graph graph() {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                List<StateView> states = new ArrayList<>();
                Result nodes = tx.run("MATCH (s:State) RETURN s.code AS code, s.name AS name, s.region AS region ORDER BY code");
                while (nodes.hasNext()) {
                    Record r = nodes.next();
                    states.add(new StateView(r.get("code").asString(), r.get("name").asString(null), nullable(r.get("region"))));
                }
                List<LinkView> links = new ArrayList<>();
                Result pairs = tx.run("MATCH (a:State)-[r:MOVES_WITH]-(b:State) WHERE a.code < b.code "
                        + "RETURN a.code AS a, b.code AS b, r.weight AS weight, r.confidence AS confidence, r.basis AS basis "
                        + "ORDER BY a, b");
                while (pairs.hasNext()) {
                    Record r = pairs.next();
                    links.add(new LinkView(r.get("a").asString(), r.get("b").asString(), r.get("weight").asDouble(),
                            r.get("confidence").isNull() ? null : r.get("confidence").asDouble(),
                            r.get("basis").isNull() ? List.of() : r.get("basis").asList(Value::asString)));
                }
                return new Graph(artifact.version(), artifact.graph().asOf(), states, links, artifact.graph().regions(),
                        artifact.validationHeadline());
            });
        }
    }

    private static String nullable(Value v) {
        return v.isNull() ? null : v.asString();
    }

    /**
     * Every state reachable within {@code maxHops} of {@code state} over the learned links, with its shortest hop
     * distance. Hops, not a weighted distance: the links are partial correlations of a similar size, so a weighted
     * distance would rank pairs by noise.
     *
     * <p>{@code shortestPath} runs a breadth-first search per target, instead of enumerating every path up to the
     * hop limit. Neo4j does not support parameterizing the bounds of a variable-length pattern ({@code *..$maxHops}
     * is a syntax error), so {@code maxHops} is inlined into the query text. That is safe because it is an int
     * range-checked by the controller (@Min(1) @Max(5)), not user text.
     */
    public List<SegmentNeighbor> neighbors(String state, int maxHops) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(
                        "MATCH (a:State {code: $source}), (b:State) WHERE b.code <> $source "
                                + "MATCH p = shortestPath((a)-[:MOVES_WITH*.." + maxHops + "]-(b)) "
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

    /** Version and checksum prefix: the version names the data, so a re-export from the same data with different
     * code keeps it, while the checksum moves with any change to the file. */
    public String cacheTag() {
        return artifact.version() + ":" + artifact.sha256().substring(0, 12);
    }
}
