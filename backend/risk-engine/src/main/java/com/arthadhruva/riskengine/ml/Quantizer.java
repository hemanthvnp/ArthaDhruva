package com.arthadhruva.riskengine.ml;

import java.util.List;
import java.util.Map;

/**
 * Rounds continuous model inputs to the grid they were trained on, exactly as the training scripts do
 * ({@code numpy.rint(x * 10^d) / 10^d}, round-half-to-even, then float32; a value the training data holds as a
 * float32 column is narrowed to float32 first, see {@link #roundAfterFloat32}). The models' split thresholds sit
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

    /** Rounds a float64 value to the grid, then casts to float32 (the value is never narrowed first). */
    public static float round(double value, int decimals) {
        double s = Math.pow(10, decimals);
        return (float) (Math.rint(value * s) / s);
    }

    /**
     * Rounds a value the training pipeline holds as a float32 column before it quantizes it: the float64 value is
     * narrowed to float32 first, and that float32 is rounded to the grid (in float64, half to even) and cast back.
     * This is how training builds {@code rate_spread} (credit_common.with_spread subtracts in float64, then casts the
     * result to Float32, and the grid is applied to that column). The order matters only on an exact tie such as
     * -0.4175, where the float32 representation error, not half-to-even, decides which way it falls (-0.417, where
     * rounding the double would give -0.418).
     */
    public static float roundAfterFloat32(double value, int decimals) {
        double s = Math.pow(10, decimals);
        return (float) (Math.rint((double) (float) value * s) / s);
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
