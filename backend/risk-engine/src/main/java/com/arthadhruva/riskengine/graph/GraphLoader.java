package com.arthadhruva.riskengine.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts the learned segment graph ({@link SegmentGraphArtifact}) into Neo4j at startup, replacing whatever segment
 * graph is there.
 *
 * <p><b>Versioned.</b> A {@code (:SegmentGraph {version, sha256})} node records what is loaded. When it matches the
 * artifact and the stored states and relationships are exactly the file's, nothing is written. Otherwise one write transaction deletes every {@code State} (with its
 * relationships) and the old marker, then creates the states, the {@code MOVES_WITH} links (and {@code LEADS}, if
 * the file has any) and the new marker. MERGE alone would not do: it never deletes, so the old correlation graph's
 * {@code CORRELATES} edges would stay next to the learned links. One transaction means a reader sees the old graph
 * or the new one, never half; replicas starting together are serialised by Neo4j and write the same content.
 *
 * <p>Failures are logged and swallowed, not rethrown: an {@link ApplicationRunner} exception is fatal to
 * {@code SpringApplication.run()}, which would take down PD/CVaR/LGD/LSTM serving too if Neo4j merely happens to be
 * slow or unavailable at boot. A Neo4j outage only degrades the {@code /segments} endpoints, which then fail per
 * request until a later start succeeds. The artifact itself has already been held to its card by
 * {@link com.arthadhruva.riskengine.governance.ModelRegistry}; a mismatch there does stop the application.
 */
@Component
public class GraphLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphLoader.class);

    private final Driver driver;
    private final SegmentGraphArtifact artifact;

    public GraphLoader(Driver driver, SegmentGraphArtifact artifact) {
        this.driver = driver;
        this.artifact = artifact;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            load();
        } catch (Exception e) {
            log.warn("Failed to load the segment graph into Neo4j -- /segments endpoints will not work until this "
                    + "succeeds on a future restart", e);
        }
    }

    /** Loads the artifact unless Neo4j already holds it. Returns whether anything was written. */
    public boolean load() {
        if (loaded().filter(this::matches).isPresent()) {
            log.info("Segment graph {} already loaded", artifact.version());
            return false;
        }
        SegmentGraphArtifact.Graph g = artifact.graph();
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (s:State) DETACH DELETE s");
                tx.run("MATCH (m:SegmentGraph) DELETE m");
                tx.run("UNWIND $states AS s CREATE (:State {code: s.code, name: s.name, region: s.region, covered: s.covered})",
                        Map.of("states", g.states().stream().map(GraphLoader::state).toList()));
                tx.run("UNWIND $links AS l MATCH (a:State {code: l.a}), (b:State {code: l.b}) "
                                + "CREATE (a)-[:MOVES_WITH {weight: l.weight, sign: l.sign, confidence: l.confidence, basis: l.basis}]->(b)",
                        Map.of("links", g.movesWith().stream().map(GraphLoader::link).toList()));
                tx.run("UNWIND $leads AS l MATCH (a:State {code: l.from}), (b:State {code: l.to}) "
                                + "CREATE (a)-[:LEADS {lagMonths: l.lagMonths, weight: l.weight, sign: l.sign, "
                                + "confidence: l.confidence, basis: l.basis}]->(b)",
                        Map.of("leads", g.leads().stream().map(GraphLoader::lead).toList()));
                tx.run("CREATE (:SegmentGraph {version: $version, sha256: $sha256})",
                        Map.of("version", artifact.version(), "sha256", artifact.sha256()));
                return null;
            });
        }
        log.info("Loaded segment graph {}: {} states, {} links, {} leads", artifact.version(), g.states().size(),
                g.movesWith().size(), g.leads().size());
        return true;
    }

    /** The marker names this artifact, and the states and relationships are exactly the file's: something written
     * after a load (the retired Pearson refresh, a manual edit) makes the next start replace the graph again. */
    private boolean matches(Record marker) {
        if (!artifact.version().equals(marker.get("version").asString())
                || !artifact.sha256().equals(marker.get("sha256").asString())) {
            return false;
        }
        SegmentGraphArtifact.Graph g = artifact.graph();
        try (var session = driver.session()) {
            Record counts = session.executeRead(tx -> tx.run(
                    "MATCH (s:State) WITH count(s) AS states "
                            + "OPTIONAL MATCH (:State)-[r]->(:State) "
                            + "RETURN states, count(r) AS relationships, "
                            + "sum(CASE type(r) WHEN 'MOVES_WITH' THEN 1 ELSE 0 END) AS moves, "
                            + "sum(CASE type(r) WHEN 'LEADS' THEN 1 ELSE 0 END) AS leads").single());
            return counts.get("states").asLong() == g.states().size()
                    && counts.get("moves").asLong() == g.movesWith().size()
                    && counts.get("leads").asLong() == g.leads().size()
                    && counts.get("relationships").asLong() == g.movesWith().size() + g.leads().size();
        }
    }

    private Optional<Record> loaded() {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                List<Record> markers = tx.run("MATCH (m:SegmentGraph) RETURN m.version AS version, m.sha256 AS sha256").list();
                return markers.size() == 1 ? Optional.of(markers.get(0)) : Optional.empty();
            });
        }
    }

    // Neo4j parameters cannot hold Java records; maps (with null for a missing region) can.
    private static Map<String, Object> state(SegmentGraphArtifact.State s) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", s.code());
        m.put("name", s.name());
        m.put("region", s.region());
        m.put("covered", s.covered());
        return m;
    }

    private static Map<String, Object> link(SegmentGraphArtifact.Link l) {
        Map<String, Object> m = new HashMap<>();
        m.put("a", l.a());
        m.put("b", l.b());
        m.put("weight", l.weight());
        m.put("sign", l.sign());
        m.put("confidence", l.confidence());
        m.put("basis", l.basis() == null ? List.of() : new ArrayList<>(l.basis()));
        return m;
    }

    private static Map<String, Object> lead(SegmentGraphArtifact.Lead l) {
        Map<String, Object> m = new HashMap<>();
        m.put("from", l.from());
        m.put("to", l.to());
        m.put("lagMonths", l.lagMonths());
        m.put("weight", l.weight());
        m.put("sign", l.sign());
        m.put("confidence", l.confidence());
        m.put("basis", l.basis() == null ? List.of() : new ArrayList<>(l.basis()));
        return m;
    }
}
