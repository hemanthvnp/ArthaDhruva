package com.arthadhruva.riskengine.ml;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeatureVectorBuilderTest {

    private static final List<String> NUMERIC = List.of("a", "b");
    private static final List<String> CATEGORICAL = List.of("c", "d");
    private static final List<String> BOOLEAN = List.of("e");
    /** Mutable maps, like the Jackson-parsed mappings the services pass (Map.of rejects a null lookup). */
    private static final Map<String, Map<String, Integer>> MAPPINGS = Map.of(
            "c", new HashMap<>(Map.of("X", 0, "Y", 1)), "d", new HashMap<>(Map.of("P", 0, "Q", 7)));

    /** The model's column order, deliberately not grouped by type or in the callers' array order. */
    private final FeatureVectorBuilder builder = new FeatureVectorBuilder(
            List.of("d", "b", "e", "a", "c"), NUMERIC, CATEGORICAL, BOOLEAN, MAPPINGS);

    @Test
    void placesEveryValueInTheModelsColumnOrder() {
        float[] vector = builder.build(new float[]{1.5f, 2.5f}, new String[]{"Y", "Q"}, new boolean[]{true});
        assertArrayEquals(new float[]{7f, 2.5f, 1f, 1.5f, 1f}, vector, 0.0f);
        assertEquals(5, builder.size());
    }

    @Test
    void booleansEncodeAsOneAndZero() {
        assertEquals(0f, builder.build(new float[]{0, 0}, new String[]{"X", "P"}, new boolean[]{false})[2], 0.0f);
    }

    /** Unseen and missing categories take -1, as LightGBM and the training scripts' replace_strict(default=-1) do. */
    @Test
    void unseenAndNullCategoriesEncodeAsMinusOne() {
        float[] vector = builder.build(new float[]{0, 0}, new String[]{"nope", null}, new boolean[]{false});
        assertEquals(-1f, vector[0], 0.0f, "d, unseen");
        assertEquals(-1f, vector[4], 0.0f, "c, null");
    }

    @Test
    void missingNumericValuesPassThroughAsNaN() {
        float[] vector = builder.build(new float[]{Float.NaN, 2f}, new String[]{"X", "P"}, new boolean[]{false});
        assertEquals(true, Float.isNaN(vector[3]));
    }

    @Test
    void failsAtConstructionNotAtFirstRequest() {
        IllegalStateException unclassified = assertThrows(IllegalStateException.class, () -> new FeatureVectorBuilder(
                List.of("a", "ghost"), NUMERIC, CATEGORICAL, BOOLEAN, MAPPINGS));
        assertEquals("Feature not classified as numeric/categorical/boolean: ghost", unclassified.getMessage());

        Map<String, Map<String, Integer>> withoutD = new HashMap<>(MAPPINGS);
        withoutD.remove("d");
        IllegalStateException unmapped = assertThrows(IllegalStateException.class, () -> new FeatureVectorBuilder(
                List.of("a", "d"), NUMERIC, CATEGORICAL, BOOLEAN, withoutD));
        assertEquals("No category mapping for feature: d", unmapped.getMessage());
    }
}
