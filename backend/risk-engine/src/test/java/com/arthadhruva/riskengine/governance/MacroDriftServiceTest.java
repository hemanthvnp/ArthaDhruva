package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The survival model's macro drivers against its training window, on the macro data that ships with it. */
class MacroDriftServiceTest {

    private static MarketData market;
    private static SurvivalModel model;
    private static MacroDriftService service;

    @BeforeAll
    static void load() throws Exception {
        market = new MarketData();
        model = new SurvivalModel(true);
        service = new MacroDriftService(model, market);
    }

    private static MacroDriftService.Driver driver(MacroDriftService.MacroDriftReport report, String feature) {
        return report.drivers().stream().filter(d -> d.feature().equals(feature)).findFirst().orElseThrow();
    }

    @Test
    void theReferenceIsSplitIntoQuintilesOfTheTrainingWindow() {
        MacroDriftService.MacroDriftReport report = service.report(List.of());
        assertEquals(MacroDriftService.DRIVERS.size(), report.drivers().size());
        assertEquals(market.states().size(), report.states());
        for (MacroDriftService.Driver d : report.drivers()) {
            double total = 0;
            for (DriftService.Bin bin : d.drift().bins()) {
                assertEquals(0.2, bin.expected(), 0.05, d.feature() + " " + bin.label());
                total += bin.expected();
            }
            assertEquals(1.0, total, 1e-9);
            assertEquals((MacroDriftService.BINS - 1) / (double) report.states(), d.drift().noiseFloor(), 1e-12,
                    "every quintile is occupied in the reference, so the floor is (bins - 1) / states");
        }
    }

    /** January 2009: house prices falling in every state at once, the bottom of the window's range. */
    @Test
    void aCrisisMonthReadsAsASignificantShiftInHousePrices() {
        MacroDriftService.MacroDriftReport crisis = service.report(YearMonth.of(2009, 1), List.of());
        MacroDriftService.Driver hpi = driver(crisis, "hpi_change_12m");
        assertEquals("SIGNIFICANT", hpi.drift().status());
        assertTrue(hpi.currentMedian() < 0, "the median state's prices fell over the year");
        assertTrue(hpi.drift().bins().get(0).actual() > 0.5, "most states sit in the window's bottom quintile");
    }

    @Test
    void statesOutsideTheEnvelopeAndTheLoansInThemAreCounted() {
        YearMonth month = YearMonth.of(2020, 5);   // the pandemic jump in unemployment
        List<String> states = List.copyOf(market.states());
        List<String> loans = new ArrayList<>();
        for (String state : states) {
            loans.add(state);
            loans.add(state);
        }
        loans.add("ZZ");   // a state with no macro data cannot be projected and is not counted as covered
        MacroDriftService.MacroDriftReport report = service.report(month, loans);
        assertEquals(loans.size(), report.loans());
        assertEquals(loans.size() - 1, report.loansCovered());
        for (MacroDriftService.Driver d : report.drivers()) {
            SurvivalModel.Range envelope = model.envelope(d.feature());
            int expectedOutside = 0;
            for (String state : states) {
                double value = switch (d.feature()) {
                    case "unemployment" -> market.unemployment(state, month);
                    case "unemployment_change_12m" -> market.unemployment(state, month) - market.unemployment(state, month.minusMonths(12));
                    default -> market.hpi(state, month) / market.hpi(state, month.minusMonths(12)) - 1.0;
                };
                if (value < envelope.min() || value > envelope.max()) {
                    expectedOutside++;
                    assertTrue(d.statesOutside().contains(state), d.feature() + " " + state);
                }
            }
            assertEquals(expectedOutside, d.statesOutside().size(), d.feature());
            assertEquals(2 * expectedOutside, d.loansOutside(), "two loans in each state outside");
            assertTrue(d.currentMin() <= d.currentMedian() && d.currentMedian() <= d.currentMax());
        }
        assertTrue(driver(report, "unemployment_change_12m").statesOutside().size() > 0,
                "May 2020's twelve-month jump in unemployment is beyond anything in the training sample for some states");
    }

    @Test
    void theMarketRateIsPlacedInTheTrainingWindowsDistribution() {
        MacroDriftService.MarketRate rate = service.report(List.of()).marketRate();
        assertTrue(rate.percentile() >= 0 && rate.percentile() <= 1);
        assertTrue(rate.trainingMin() < rate.trainingMax());
        assertEquals(rate.current() < rate.trainingMin() || rate.current() > rate.trainingMax(), rate.outsideTraining());
        assertEquals(market.rateAsOf().toString(), rate.asOf());
    }

    @Test
    void quantilesInterpolateBetweenOrderStatistics() {
        double[] sorted = {1, 2, 3, 4, 5};
        assertEquals(1, MacroDriftService.quantile(sorted, 0), 0);
        assertEquals(3, MacroDriftService.quantile(sorted, 0.5), 0);
        assertEquals(1.8, MacroDriftService.quantile(sorted, 0.2), 1e-12);
        assertEquals(5, MacroDriftService.quantile(sorted, 1), 0);
    }
}
