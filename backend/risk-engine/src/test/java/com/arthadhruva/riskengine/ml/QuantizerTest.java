package com.arthadhruva.riskengine.ml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rounding must be numpy's ({@code rint}: half to even, in double precision, then float32). */
class QuantizerTest {

    private final Quantizer quantizer = new Quantizer(List.of("spread", "ltv", "state", "age"),
            Map.of("spread", 3, "ltv", 2, "age", 0));

    @Test
    void roundsHalfToEvenLikeNumpy() {
        assertEquals(0.0f, Quantizer.round(0.5, 0), 0.0f);
        assertEquals(2.0f, Quantizer.round(1.5, 0), 0.0f);
        assertEquals(2.0f, Quantizer.round(2.5, 0), 0.0f);
        assertEquals(-2.0f, Quantizer.round(-2.5, 0), 0.0f);
        assertEquals(0.125f, Quantizer.round(0.125, 3), 0.0f);
        assertEquals((float) 0.059, Quantizer.round(0.05949999, 3), 0.0f);
        assertEquals((float) 0.06, Quantizer.round(0.0595000001, 3), 0.0f);
    }

    /** A value is rounded from its double, not after a float cast that may already have moved it. */
    @Test
    void valueRoundsBeforeTheFloatCast() {
        assertEquals((float) 80.13, quantizer.value(1, 80.125000001), 0.0f);
        assertEquals((float) 80.12, quantizer.value(1, 80.124999999), 0.0f);
        assertEquals(17.0f, quantizer.value(3, 17.49), 0.0f);
        assertEquals(1.25f, quantizer.value(2, 1.25), 0.0f, "a feature with no grid passes through");
    }

    @Test
    void applyQuantizesInPlaceAndLeavesUngriddedAndMissingValuesAlone() {
        float[] vector = {0.12345f, 80.126f, 12f, 3.6f};
        float[] same = quantizer.apply(vector);
        assertTrue(same == vector);
        assertArrayEquals(new float[]{(float) 0.123, (float) 80.13, 12f, 4f}, vector, 0.0f);

        float[] withMissing = {Float.NaN, 1f, -1f, Float.NaN};
        quantizer.apply(withMissing);
        assertTrue(Float.isNaN(withMissing[0]) && Float.isNaN(withMissing[3]));
        assertEquals(-1f, withMissing[2], 0.0f);
    }

    /** Applying the grid twice changes nothing: a value on the grid stays on it. */
    @Test
    void quantizationIsIdempotent() {
        for (double v = -3.0; v < 3.0; v += 0.00173) {
            float once = Quantizer.round(v, 3);
            assertEquals(once, Quantizer.round(once, 3), 0.0f, "v = " + v);
        }
    }
}
