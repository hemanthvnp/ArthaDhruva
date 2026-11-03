package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.arthadhruva.riskengine.survival.SurvivalFixtures.ENGINE;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.prime;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 5.6: given a state model whose current-state default and prepayment probabilities equal the existing hazards and
 * whose delinquent transitions are disabled (a current loan stays current, or defaults, or prepays), the multi-state
 * engine's figures equal the existing engine's within 1e-9. The shared logic (scenario path, exposure, loss given default
 * with its collateral floor, discounting, staging) is therefore the same logic, not a copy that has drifted.
 */
class MultiStateEquivalenceTest {

    private static final double TOLERANCE = 1e-9;

    /** The existing engine's hazards as a transition model: from current, (stay, 0, 0, default, prepay); delinquent rows are inert. */
    private static double[] asTransitionProbabilities(double[] hazards, int survivalRows) {
        double[] p = new double[survivalRows * DelinquencyModel.STATES * DelinquencyModel.CLASSES];
        for (int j = 0; j < survivalRows; j++) {
            double hd = hazards[2 * j], hp = hazards[2 * j + 1];
            for (int s = 0; s < DelinquencyModel.STATES; s++) {
                int base = (j * DelinquencyModel.STATES + s) * DelinquencyModel.CLASSES;
                p[base + s] = 1.0 - hd - hp;      // stay in the same state: a current loan stays current
                p[base + DelinquencyModel.DEFAULT] = hd;
                p[base + DelinquencyModel.PREPAID] = hp;
            }
        }
        return p;
    }

    @Test
    void withDelinquentTransitionsDisabledTheEngineEqualsTheExistingOne() {
        List<LoanFeatures> loans = List.of(prime(), loan("weak", 640, 95.0, 7.25, 360, 30.0, "FL", null),
                loan("seasoned", 720, 80.0, 3.0, 360, 0.0, "TX", "2021-06"), loan("fifteen", 780, 60.0, 5.75, 180, 0.0, "OH", null));
        List<Scenario> scenarios = new ArrayList<>(Scenario.BUILT_IN);
        MultiStateEngine multi = DelinquencyFixtures.ENGINE;
        for (LoanFeatures loan : loans) {
            for (Scenario scenario : scenarios) {
                TermStructureEngine.Request base = new TermStructureEngine.Request(loan, scenario, null, null, null, null);
                TermStructureEngine.Outcome existing = ENGINE.projectAll(List.of(base)).get(0);

                TermStructureEngine.Projection projection = ENGINE.prepare(base);
                float[] flat = new float[projection.rows() * ENGINE.features()];
                projection.writeRows(flat, 0);
                double[] hazards = ENGINE.hazards(flat, projection.rows());
                MultiStateEngine.Prepared prepared = multi.prepare(new MultiStateEngine.Request(base, DelinquencyModel.CURRENT, MultiStateEngine.NO_HISTORY));
                MultiStateEngine.Outcome got = multi.finish(prepared, asTransitionProbabilities(hazards, projection.rows()), 0);

                String at = loan.loanId() + " / " + scenario.name();
                TermStructureEngine.Summary e = existing.summary(), g = got.summary();
                assertEquals(e.pd12m(), g.pd12m(), TOLERANCE, at + " pd12m");
                assertEquals(e.pd24m(), g.pd24m(), TOLERANCE, at + " pd24m");
                assertEquals(e.pdLifetime(), g.pdLifetime(), TOLERANCE, at + " pdLifetime");
                assertEquals(e.prepayLifetime(), g.prepayLifetime(), TOLERANCE, at + " prepayLifetime");
                assertEquals(e.expectedLifeMonths(), g.expectedLifeMonths(), TOLERANCE, at + " expected life");
                assertEquals(e.maturityProbability(), g.maturityProbability(), TOLERANCE, at + " maturity");
                TermStructureEngine.Ecl el = existing.ecl(), gl = got.ecl();
                assertEquals(el.stage(), gl.stage(), at + " stage");
                assertEquals(el.exposure(), gl.exposure(), TOLERANCE, at + " exposure");
                assertEquals(el.lgdPeak(), gl.lgdPeak(), TOLERANCE, at + " lgdPeak");
                assertEquals(el.ecl12m(), gl.ecl12m(), TOLERANCE, at + " ecl12m");
                assertEquals(el.eclLifetime(), gl.eclLifetime(), TOLERANCE, at + " eclLifetime");
                assertEquals(el.eclIfrs9(), gl.eclIfrs9(), TOLERANCE, at + " eclIfrs9");
                assertEquals(el.eclCecl(), gl.eclCecl(), TOLERANCE, at + " eclCecl");
                assertEquals(el.referencePd12m() == null, gl.referencePd12m() == null, at + " reference");
                if (el.referencePd12m() != null) {
                    assertEquals(el.referencePd12m(), gl.referencePd12m(), TOLERANCE, at + " reference PD");
                }
            }
        }
    }
}
