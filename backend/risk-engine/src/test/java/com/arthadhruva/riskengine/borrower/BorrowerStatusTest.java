package com.arthadhruva.riskengine.borrower;

import com.arthadhruva.riskengine.score.LoanScoreId;
import com.arthadhruva.riskengine.score.LoanScoreRecord;
import com.arthadhruva.riskengine.score.RiskBand;
import com.arthadhruva.riskengine.workflow.LoanCaseStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a borrower is told about their loan's risk: a band and how it moved, never a figure. */
class BorrowerStatusTest {

    private static final Instant MARCH = Instant.parse("2026-03-04T10:00:00Z");
    private static final Instant MAY = Instant.parse("2026-05-20T10:00:00Z");
    private static final Instant JULY = Instant.parse("2026-07-01T10:00:00Z");

    @Test
    void bandsUseTheConsoleThresholds() {
        assertEquals(RiskBand.LOW, RiskBand.of(0.0));
        assertEquals(RiskBand.LOW, RiskBand.of(0.0199));
        assertEquals(RiskBand.MEDIUM, RiskBand.of(0.02));
        assertEquals(RiskBand.MEDIUM, RiskBand.of(0.0999));
        assertEquals(RiskBand.HIGH, RiskBand.of(0.10));
        assertEquals(RiskBand.HIGH, RiskBand.of(0.6));
    }

    @Test
    void theRecordKeepsTheBandBeforeAndSinceWhenTheCurrentOneHolds() {
        LoanScoreRecord record = new LoanScoreRecord(new LoanScoreId(1L, "L-1"), 0.1, 0.01, MARCH, "v1");
        assertEquals(RiskBand.LOW, record.getBand());
        assertEquals(MARCH, record.getBandSince());
        assertNull(record.getPreviousBand());

        record.update(0.1, 0.015, MAY, "v1");   // still low: nothing moves
        assertEquals(MARCH, record.getBandSince());
        assertNull(record.getPreviousBand());

        record.update(0.3, 0.05, MAY, "v1");    // up to medium
        assertEquals(RiskBand.MEDIUM, record.getBand());
        assertEquals(RiskBand.LOW, record.getPreviousBand());
        assertEquals(MAY, record.getBandSince());

        record.update(0.1, 0.01, JULY, "v2");   // back down
        assertEquals(RiskBand.LOW, record.getBand());
        assertEquals(RiskBand.MEDIUM, record.getPreviousBand());
        assertEquals(JULY, record.getBandSince());
    }

    @Test
    void theTrendIsASentenceWithAMonthAndNoFigure() {
        assertEquals("Unchanged since March 2026.", BorrowerPortalService.trend(RiskBand.LOW, null, MARCH));
        assertEquals("Up from Good standing in May 2026.", BorrowerPortalService.trend(RiskBand.MEDIUM, RiskBand.LOW, MAY));
        assertEquals("Improved from Elevated risk in July 2026.", BorrowerPortalService.trend(RiskBand.MEDIUM, RiskBand.HIGH, JULY));
        assertEquals(BorrowerPortalService.NO_HISTORY, BorrowerPortalService.trend(RiskBand.HIGH, null, null));
        for (RiskBand band : RiskBand.values()) {
            for (RiskBand previous : new RiskBand[]{null, RiskBand.LOW, RiskBand.MEDIUM, RiskBand.HIGH}) {
                String trend = BorrowerPortalService.trend(band, previous, MAY);
                assertFalse(trend.contains("%"), trend);
                assertFalse(trend.replace("2026", "").matches(".*\\d.*"), "the only number is the year: " + trend);
            }
        }
    }

    @Test
    void aStatusIsBuiltFromTheBandNotTheProbability() {
        LoanScoreRecord record = new LoanScoreRecord(new LoanScoreId(1L, "L-1"), 0.4, 0.2, MAY, "v1");
        BorrowerPortalService.Status status = BorrowerPortalService.status(record);
        assertEquals(RiskBand.HIGH, status.band());
        assertEquals("Elevated risk", status.label());
        assertEquals("Unchanged since May 2026.", status.trend());
        assertEquals(MAY, status.checkedAt());
    }

    @Test
    void caseStatusesAreSaidInPlainWords() {
        assertEquals("Received", BorrowerPortalService.caseLabel(LoanCaseStatus.NEW));
        assertEquals("Under review", BorrowerPortalService.caseLabel(LoanCaseStatus.REVIEWED));
        assertEquals("With a senior reviewer", BorrowerPortalService.caseLabel(LoanCaseStatus.ESCALATED));
    }

    /** The guarantee is in the response types: a field that is not there cannot be shown by devtools either. */
    @Test
    void noBorrowerResponseTypeCarriesAScoreModelOrExplanationField() {
        for (Class<?> type : List.of(BorrowerPortalService.MyLoanView.class, BorrowerPortalService.Status.class,
                BorrowerPortalService.OpenCase.class, BorrowerPortalService.BorrowerDocument.class,
                BorrowerContactService.Contact.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                for (String banned : List.of("probab", "score", "model", "explan", "shap", "note", "reason", "flag")) {
                    assertFalse(name.contains(banned), type.getSimpleName() + "." + component.getName());
                }
            }
        }
    }
}
