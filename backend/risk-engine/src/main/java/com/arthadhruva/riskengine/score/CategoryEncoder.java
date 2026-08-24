package com.arthadhruva.riskengine.score;

import java.util.Map;

/**
 * Encodes a categorical string value to the integer code LightGBM used internally at training
 * time (see export_model.py -- category_mappings.json is the exact string->code mapping the
 * Python training script produced). An unseen category maps to -1, matching LightGBM's own
 * convention for unseen/missing categorical values.
 */
public final class CategoryEncoder {

    private final Map<String, Map<String, Integer>> mappingsByFeature;

    public CategoryEncoder(Map<String, Map<String, Integer>> mappingsByFeature) {
        this.mappingsByFeature = mappingsByFeature;
    }

    public float encode(String featureName, String rawValue) {
        Map<String, Integer> mapping = mappingsByFeature.get(featureName);
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown categorical feature: " + featureName);
        }
        return mapping.getOrDefault(rawValue, -1).floatValue();
    }
}
