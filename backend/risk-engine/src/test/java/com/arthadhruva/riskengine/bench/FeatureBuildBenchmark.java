package com.arthadhruva.riskengine.bench;

import com.arthadhruva.riskengine.ml.FeatureVectorBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Before/after for design item 9.6: the original per-request feature-vector construction
 * (a boxed Map<String,Float> per call plus List.contains per feature) vs FeatureVectorBuilder.
 * Run: {@code mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=...} or the
 * jar-less runner in main(). Setup asserts both produce identical vectors before timing anything.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FeatureBuildBenchmark {

    private static final List<String> NUMERIC = List.of("credit_score", "original_dti", "original_upb", "original_cltv",
            "original_ltv", "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent");
    private static final List<String> CATEGORICAL = List.of("occupancy_status", "property_type", "loan_purpose",
            "channel", "first_time_homebuyer_flag", "property_state");

    private List<String> allFeatures;
    private Map<String, Map<String, Integer>> mappings;
    private FeatureVectorBuilder builder;

    private final float[] numeric = {720f, 38f, 250000f, 80f, 80f, 6.5f, 360f, 2f, 1f, 0f};
    private final String[] categorical = {"P", "SF", "P", "R", "N", "CA"};
    private final boolean[] noBooleans = new boolean[0];

    @Setup
    public void setup() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode order = mapper.readTree(resource("feature_order.json"));
        allFeatures = new ArrayList<>();
        order.get("all_features_in_order").forEach(n -> allFeatures.add(n.asString()));
        mappings = mapper.readValue(resource("category_mappings.json"), new TypeReference<>() {});
        builder = new FeatureVectorBuilder(allFeatures, NUMERIC, CATEGORICAL, List.of(), mappings);

        if (!Arrays.equals(legacy(), builder.build(numeric, categorical, noBooleans))) {
            throw new IllegalStateException("new builder disagrees with the legacy implementation");
        }
    }

    @Benchmark
    public float[] legacyMapAndListContains() {
        return legacy();
    }

    @Benchmark
    public float[] precomputedLayout() {
        return builder.build(numeric, categorical, noBooleans);
    }

    private float[] legacy() {
        Map<String, Float> numericValues = new HashMap<>();
        for (int i = 0; i < NUMERIC.size(); i++) {
            numericValues.put(NUMERIC.get(i), numeric[i]);
        }
        Map<String, String> categoricalValues = new HashMap<>();
        for (int i = 0; i < CATEGORICAL.size(); i++) {
            categoricalValues.put(CATEGORICAL.get(i), categorical[i]);
        }
        float[] vector = new float[allFeatures.size()];
        for (int i = 0; i < allFeatures.size(); i++) {
            String feature = allFeatures.get(i);
            if (NUMERIC.contains(feature)) {
                vector[i] = numericValues.get(feature);
            } else {
                Integer code = mappings.get(feature).get(categoricalValues.get(feature));
                vector[i] = code == null ? -1f : code;
            }
        }
        return vector;
    }

    private static InputStream resource(String name) {
        return FeatureBuildBenchmark.class.getClassLoader().getResourceAsStream(name);
    }

    public static void main(String[] args) throws Exception {
        Options opts = new OptionsBuilder().include(FeatureBuildBenchmark.class.getSimpleName())
                .addProfiler("gc").build();
        new Runner(opts).run();
    }
}
