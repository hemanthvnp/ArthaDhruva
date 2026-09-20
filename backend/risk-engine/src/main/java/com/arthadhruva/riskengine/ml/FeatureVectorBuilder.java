package com.arthadhruva.riskengine.ml;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a model's typed inputs into the flat float vector ONNX expects, in O(F) per request with
 * no per-request maps or boxing. The previous implementation rebuilt a {@code Map<String,Float>}
 * (boxing every value) on each call and, per feature, ran {@code List.contains} over the feature
 * lists, O(F^2) string comparisons per request.
 *
 * <p>Everything that depends only on the model's fixed feature layout is resolved once, at
 * construction: for each output position, which input array it reads from and at what index, and
 * (for categoricals) the category-to-code map. The hot path is then array indexing plus one hash
 * lookup per categorical. Also fails fast at startup, not at first request, if the model's
 * feature list names something the caller's canonical order doesn't provide.
 */
public final class FeatureVectorBuilder {

    private static final byte NUMERIC = 0;
    private static final byte CATEGORICAL = 1;
    private static final byte BOOLEAN = 2;

    private final byte[] kind;
    private final int[] slot;
    private final Map<String, Integer>[] categoryCodes;

    /**
     * @param allFeaturesInOrder the model's feature order (from feature_order.json)
     * @param numericNames       the caller's canonical order of the numeric input array
     * @param categoricalNames   the caller's canonical order of the categorical input array
     * @param booleanNames       the caller's canonical order of the boolean input array (may be empty)
     */
    @SuppressWarnings("unchecked")
    public FeatureVectorBuilder(List<String> allFeaturesInOrder, List<String> numericNames,
                                List<String> categoricalNames, List<String> booleanNames,
                                Map<String, Map<String, Integer>> categoryMappings) {
        int n = allFeaturesInOrder.size();
        this.kind = new byte[n];
        this.slot = new int[n];
        this.categoryCodes = (Map<String, Integer>[]) new Map[n];

        Map<String, Integer> numericIndex = indexOf(numericNames);
        Map<String, Integer> categoricalIndex = indexOf(categoricalNames);
        Map<String, Integer> booleanIndex = indexOf(booleanNames);

        for (int i = 0; i < n; i++) {
            String feature = allFeaturesInOrder.get(i);
            if (numericIndex.containsKey(feature)) {
                kind[i] = NUMERIC;
                slot[i] = numericIndex.get(feature);
            } else if (categoricalIndex.containsKey(feature)) {
                kind[i] = CATEGORICAL;
                slot[i] = categoricalIndex.get(feature);
                Map<String, Integer> codes = categoryMappings.get(feature);
                if (codes == null) {
                    throw new IllegalStateException("No category mapping for feature: " + feature);
                }
                categoryCodes[i] = codes;
            } else if (booleanIndex.containsKey(feature)) {
                kind[i] = BOOLEAN;
                slot[i] = booleanIndex.get(feature);
            } else {
                throw new IllegalStateException("Feature not classified as numeric/categorical/boolean: " + feature);
            }
        }
    }

    public int size() {
        return kind.length;
    }

    /** Unseen categories encode to -1, matching LightGBM's own convention (see CategoryEncoder). */
    public float[] build(float[] numeric, String[] categorical, boolean[] booleans) {
        float[] vector = new float[kind.length];
        for (int i = 0; i < vector.length; i++) {
            switch (kind[i]) {
                case NUMERIC -> vector[i] = numeric[slot[i]];
                case CATEGORICAL -> {
                    Integer code = categoryCodes[i].get(categorical[slot[i]]);
                    vector[i] = code == null ? -1f : code;
                }
                default -> vector[i] = booleans[slot[i]] ? 1f : 0f;
            }
        }
        return vector;
    }

    private static Map<String, Integer> indexOf(List<String> names) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        return index;
    }
}
