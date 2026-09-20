package com.arthadhruva.riskengine.score;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplanationServiceTest {

    private static LoanFeatures loan(int credit, double ltv, double dti, String state) {
        return new LoanFeatures("T", credit, dti, 250000.0, ltv, ltv, 6.5, 360, 2, 1, 0.0, "P", "SF", "P", "R", "N", state);
    }

    @Test
    void explanationsAreLoanSpecificAndPointTheRightWay() throws Exception {
        ModelService model = new ModelService();
        ExplanationService explainer = new ExplanationService(model, new LoanCatalogService(null));

        List<ScoreResponse.Attribution> risky = explainer.explain(loan(560, 97, 48, "FL"));
        List<ScoreResponse.Attribution> safe = explainer.explain(loan(800, 55, 18, "FL"));

        assertFalse(risky.isEmpty());
        assertTrue(risky.size() <= 5);
        assertNotEquals(risky, safe, "different loans must yield different explanations");

        double riskySum = risky.stream().mapToDouble(ScoreResponse.Attribution::contribution).sum();
        double safeSum = safe.stream().mapToDouble(ScoreResponse.Attribution::contribution).sum();
        assertTrue(riskySum > safeSum, "a riskier profile's attributions should push risk up relative to a safe one");
        assertEquals("credit_score", risky.stream().max(java.util.Comparator.comparingDouble(a -> Math.abs(a.contribution()))).get().feature());
        model.close();
    }
}
