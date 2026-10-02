package com.arthadhruva.riskengine.ml;

import java.util.List;
import java.util.Map;

/**
 * Rounds continuous model inputs to the grid they were trained on, exactly as the training scripts do
 * ({@code numpy.rint(x * 10^d) / 10^d}, round-half-to-even, then float32). The models' split thresholds sit
 * between grid points, so an input on the grid takes the same tree path in LightGBM, ONNX Runtime and
 * here; an unrounded computed value (a spread, a mark-to-market LTV) could otherwise land a hair on the
 * other side of a float32-rounded threshold.
 */
public final class Quantizer {

    private final double[] scale;

    /** @param decimals feature -> decimal places; features absent from the map are left as-is */
    public Quantizer(List<String> featureOrder, Map<String, Integer> decimals) {
        this.scale = new double[featureOrder.size()];
        for (int i = 0; i < featureOrder.size(); i++) {
            Integer d = decimals.get(featureOrder.get(i));
            scale[i] = d == null ? 0 : Math.pow(10, d);
        }
    }

    public static float round(double value, int decimals) {
        double s = Math.pow(10, decimals);
        return (float) (Math.rint(value * s) / s);
    }

    /** Quantizes a feature vector in place and returns it. */
    public float[] apply(float[] vector) {
        for (int i = 0; i < vector.length; i++) {
            if (scale[i] > 0 && !Float.isNaN(vector[i])) {
                vector[i] = (float) (Math.rint((double) vector[i] * scale[i]) / scale[i]);
            }
        }
        return vector;
    }

    /** Quantizes a double-precision value for feature {@code index} (preferred: rounds before the float cast). */
    public float value(int index, double value) {
        return scale[index] > 0 ? (float) (Math.rint(value * scale[index]) / scale[index]) : (float) value;
    }
}
