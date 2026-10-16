package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.survival.TermStructureEngine.InvalidProjectionException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioWeightsTest {

    private static final List<String> COMPARED = List.of("BASELINE", "ADVERSE", "SEVERELY_ADVERSE");

    private static Map<String, Double> weights(Object... pairs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return out;
    }

    private static String rejection(Map<String, Double> weights) {
        return assertThrows(InvalidProjectionException.class, () -> ScenarioWeights.resolve(weights, COMPARED)).getMessage();
    }

    @Test
    void alignsWeightsWithTheComparedScenarios() {
        assertArrayEquals(new double[]{0.5, 0.3, 0.2},
                ScenarioWeights.resolve(weights("SEVERELY_ADVERSE", 0.2, "BASELINE", 0.5, "ADVERSE", 0.3), COMPARED), 0.0);
    }

    @Test
    void namesAreTrimmedAndCaseInsensitiveAndAScenarioLeftOutCarriesNothing() {
        double[] w = ScenarioWeights.resolve(weights(" baseline ", 0.7, "Adverse", 0.3), COMPARED);
        assertArrayEquals(new double[]{0.7, 0.3, 0.0}, w, 0.0);
        assertEquals(Map.of("BASELINE", 0.7, "ADVERSE", 0.3, "SEVERELY_ADVERSE", 0.0), ScenarioWeights.named(w, COMPARED));
        assertEquals(COMPARED, List.copyOf(ScenarioWeights.named(w, COMPARED).keySet()), "echoed in compared order");
    }

    @Test
    void rejectsWeightsThatDoNotSumToOne() {
        assertTrue(rejection(weights("BASELINE", 0.5, "ADVERSE", 0.3)).contains("must sum to 1, got 0.8"));
        assertTrue(rejection(weights("BASELINE", 0.7, "ADVERSE", 0.4)).contains("must sum to 1"));
        // decimals a person types are accepted; a visible shortfall is not
        ScenarioWeights.resolve(weights("BASELINE", 0.3333333, "ADVERSE", 0.3333333, "SEVERELY_ADVERSE", 0.3333334), COMPARED);
        assertTrue(rejection(weights("BASELINE", 0.33, "ADVERSE", 0.33, "SEVERELY_ADVERSE", 0.33)).contains("must sum to 1"));
    }

    @Test
    void rejectsAScenarioThatIsNotCompared() {
        String message = rejection(weights("BASELINE", 0.5, "RATES_UP_200", 0.5));
        assertTrue(message.contains("RATES_UP_200") && message.contains(COMPARED.toString()), message);
    }

    @Test
    void rejectsEmptyNegativeNonFiniteAndRepeatedWeights() {
        assertTrue(rejection(Map.of()).contains("at least one"));
        assertTrue(rejection(null).contains("at least one"));
        assertTrue(rejection(weights("BASELINE", 1.2, "ADVERSE", -0.2)).contains("ADVERSE"));
        assertTrue(rejection(weights("BASELINE", Double.NaN)).contains("BASELINE"));
        assertTrue(rejection(weights("BASELINE", Double.POSITIVE_INFINITY)).contains("BASELINE"));
        Map<String, Double> nullWeight = new LinkedHashMap<>();
        nullWeight.put("BASELINE", null);
        assertTrue(rejection(nullWeight).contains("BASELINE"));
        assertTrue(rejection(weights("BASELINE", 0.5, "baseline", 0.5)).contains("more than once"));
    }

    @Test
    void allTheWeightOnOneScenarioReturnsItsFigureBitForBit() {
        double[] figure = {0.1 + 0.2, 1.0 / 3.0, Math.PI};
        for (int i = 0; i < figure.length; i++) {
            double[] w = new double[figure.length];
            w[i] = 1.0;
            assertEquals(Double.doubleToLongBits(figure[i]), Double.doubleToLongBits(ScenarioWeights.combine(w, figure)));
        }
    }

    @Test
    void anEvenSplitIsTheMean() {
        double[] figure = {451.028037, 0.0, 1336.31};
        assertEquals((figure[0] + figure[2]) / 2.0, ScenarioWeights.combine(new double[]{0.5, 0.0, 0.5}, figure), 1e-12 * figure[2]);
    }

    @Test
    void theOrderTheWeightsWereSentInDoesNotChangeTheBits() {
        double[] figure = {37.591792, 88.0, 996.97};
        double a = ScenarioWeights.combine(ScenarioWeights.resolve(weights("BASELINE", 0.5, "ADVERSE", 0.3, "SEVERELY_ADVERSE", 0.2), COMPARED), figure);
        double b = ScenarioWeights.combine(ScenarioWeights.resolve(weights("SEVERELY_ADVERSE", 0.2, "ADVERSE", 0.3, "BASELINE", 0.5), COMPARED), figure);
        assertEquals(Double.doubleToLongBits(a), Double.doubleToLongBits(b));
        assertEquals(0.5 * 37.591792 + 0.3 * 88.0 + 0.2 * 996.97, a, 1e-12);
    }

    @Test
    void aFigureUnderNoWeightCannotLeakIn() {
        assertEquals(10.0, ScenarioWeights.combine(new double[]{0.5, 0.0, 0.5}, new double[]{8.0, Double.NaN, 12.0}), 0.0);
        assertEquals(8.0, ScenarioWeights.combine(new double[]{1.0, 0.0, 0.0}, new double[]{8.0, Double.POSITIVE_INFINITY, Double.NaN}), 0.0);
    }

    @Test
    void combineNeedsOneFigurePerScenario() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioWeights.combine(new double[]{1.0}, new double[]{1.0, 2.0}));
    }
}
