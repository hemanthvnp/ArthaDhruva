package com.arthadhruva.riskengine.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The learned segment graph ({@code segment_graph.json}, exported by backend/export_segment_graph.py) and its
 * model card, read once from the classpath. {@link com.arthadhruva.riskengine.governance.ModelRegistry} holds the
 * file to the checksum in the card; {@link GraphLoader} writes the parsed graph to Neo4j; the API takes the
 * validation figures from the card here.
 *
 * <p>The file may omit {@code regions} and {@code leads} (and the regional factors, which this service does not
 * use yet): they come back as empty lists. The shape is fixed by
 * {@code src/test/resources/segment/segment_graph.schema.json}.
 */
@Component
public class SegmentGraphArtifact {

    public static final String FILE = "segment_graph.json";
    public static final String CARD = "segment_graph_card.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(String code, String name, String region, boolean covered, int leads, int follows) {
    }

    /** A moves-with link: a partial correlation between {@code a} and {@code b}, with {@code a} before {@code b}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String a, String b, double weight, int sign, Double confidence, List<String> basis) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lead(String from, String to, int lagMonths, double weight, int sign, Double confidence,
                       List<String> basis) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Region(String id, String label, List<String> states, double stability) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Graph(int schemaVersion, String version, String asOf, String method, List<State> states,
                        List<Region> regions, List<Link> movesWith, List<Lead> leads) {
        public Graph {
            regions = regions == null ? List.of() : List.copyOf(regions);
            movesWith = movesWith == null ? List.of() : List.copyOf(movesWith);
            leads = leads == null ? List.of() : List.copyOf(leads);
        }
    }

    private final Graph graph;
    private final String sha256;
    private final JsonNode card;

    public SegmentGraphArtifact() throws IOException {
        this(read(FILE), read(CARD));
    }

    public SegmentGraphArtifact(byte[] file, byte[] card) {
        ObjectMapper mapper = new ObjectMapper();
        this.graph = mapper.readValue(file, Graph.class);
        this.sha256 = sha256(file);
        this.card = mapper.readTree(card);
    }

    public Graph graph() {
        return graph;
    }

    public String version() {
        return graph.version();
    }

    /** The sha256 of the bytes read, to compare with {@link #declaredSha256()}. */
    public String sha256() {
        return sha256;
    }

    public String declaredSha256() {
        return card.get("artifact_sha256").asString();
    }

    public JsonNode card() {
        return card;
    }

    /**
     * The figures the page quotes, from the card's out-of-time validation (primary series, all months): the mean
     * correlation of 3-month changes among linked pairs minus the other pairs, its p-value against degree-preserving
     * rewirings, the same against the Census-division grouping, and whether each conclusion held in every robustness
     * run. Missing figures (a later card without a comparison) come back as null.
     */
    public Map<String, Object> validationHeadline() {
        JsonNode v = card.get("validation");
        JsonNode run = v.get("runs").get("primary");
        JsonNode summary = v.get("summary");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learnedThrough", v.get("protocol").get("learned_through").asString());
        out.put("testedOn", run.get("test_months").asString());
        out.put("linkedMinusUnlinked", number(run.get("learned").get("statistic")));
        out.put("pAgainstRandomGraphs", number(run.get("learned").get("p")));
        out.put("linkedMinusSameDivision", number(run.get("learned_vs_division").get("difference")));
        out.put("pAgainstSameDivision", number(run.get("learned_vs_division").get("p")));
        out.put("robustAgainstRandomGraphs", flag(summary, "linked_pairs_move_together_more_than_random"));
        out.put("robustAgainstSameDivision", flag(summary, "adds_information_beyond_geography"));
        out.put("leadsShipped", !graph.leads().isEmpty());
        out.put("leadsNote", graph.leads().isEmpty() ? leadsNote() : null);
        return out;
    }

    private String leadsNote() {
        for (JsonNode limitation : card.get("limitations")) {
            if (limitation.asString().startsWith("Leads links are omitted")) {
                return limitation.asString();
            }
        }
        return "No leads link in this release.";
    }

    private static Boolean flag(JsonNode summary, String name) {
        JsonNode s = summary.get(name);
        return s == null || s.get("primary").isNull() ? null : s.get("primary").asBoolean() && s.get("robust").asBoolean();
    }

    private static Double number(JsonNode n) {
        return n == null || n.isNull() ? null : n.asDouble();
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream in = SegmentGraphArtifact.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " not found on the classpath");
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
