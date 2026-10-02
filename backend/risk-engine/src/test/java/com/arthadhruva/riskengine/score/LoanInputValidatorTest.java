package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.ml.MarketData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanInputValidatorTest {

    private static LoanInputValidator validator;

    @BeforeAll
    static void load() throws Exception {
        validator = new LoanInputValidator(new MarketData());
    }

    private static LoanFeatures loan(int credit, double dti, double ltv, String state, String channel, String origination) {
        return new LoanFeatures("L", credit, dti, 250000.0, ltv, ltv, 6.5, 360, 2, 1, 0.0, "P", "SF", "P", channel, "N", state, origination);
    }

    @Test
    void anOrdinaryLoanPassesWithoutWarnings() {
        assertTrue(validator.check(loan(740, 35, 80, "CA", "R", null)).warnings().isEmpty());
        assertTrue(validator.check(loan(740, 35, 80, "CA", "R", "2021-06")).warnings().isEmpty());
    }

    /** A code the model never saw used to be scored as "missing"; now it is refused, by field. */
    @Test
    void unknownCodesAndImpossibleValuesAreRejectedByField() {
        LoanInputValidator.InvalidLoanException e = assertThrows(LoanInputValidator.InvalidLoanException.class,
                () -> validator.check(loan(740, 35, 300, "ZZ", "Q", null)));
        assertEquals(java.util.Set.of("original_cltv", "original_ltv", "property_state", "channel"), e.getFields().keySet());
        assertTrue(e.getFields().get("channel").contains("'Q'"));
    }

    @Test
    void lowercaseCodesAreAcceptedOnceNormalized() {
        LoanFeatures typed = new LoanFeatures("L", 740, 35.0, 250000.0, 80.0, 80.0, 6.5, 360, 2, 1, 0.0, " p", "sf", "p", "r ", "n", "ca", null);
        assertThrows(LoanInputValidator.InvalidLoanException.class, () -> validator.check(typed));
        assertTrue(validator.check(typed.normalized()).warnings().isEmpty());
    }

    /** Unusual but possible values are scored, with a warning that the result is an extrapolation. */
    @Test
    void valuesOutsideTheTrainingRangeWarn() {
        LoanInputValidator.Result result = validator.check(loan(560, 62, 80, "CA", "R", null));
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("credit_score = 560")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("original_dti = 62")));
    }

    @Test
    void theOriginationMonthMustLieWithinTheMarketHistory() {
        String nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1).toString();
        assertTrue(assertThrows(LoanInputValidator.InvalidLoanException.class,
                () -> validator.check(loan(740, 35, 80, "CA", "R", nextMonth))).getFields().get("originationMonth").contains("future"));
        assertTrue(assertThrows(LoanInputValidator.InvalidLoanException.class,
                () -> validator.check(loan(740, 35, 80, "CA", "R", "1990-01"))).getFields().get("originationMonth").contains("1995-01"));
        validator.check(loan(740, 35, 80, "CA", "R", "1995-01"));
        validator.check(loan(740, 35, 80, "CA", "R", YearMonth.now(ZoneOffset.UTC).toString()));
    }
}
