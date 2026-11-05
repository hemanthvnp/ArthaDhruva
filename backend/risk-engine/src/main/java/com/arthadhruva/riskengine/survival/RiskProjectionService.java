package com.arthadhruva.riskengine.survival;

import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;

/**
 * One loan's projection through whichever model its status and history call for, with an optional range around the
 * lifetime figures. It is the single place that routes, so the single-loan and portfolio paths agree.
 */
@Service
public class RiskProjectionService {

    /**
     * @param modelUsed      the id and version of the model that ran
     * @param fallbackReason why the existing engine ran when delinquency data was supplied or implied (null otherwise)
     * @param intervals      the ranges, when asked for
     */
    public record Result(TermStructureEngine.TermStructure termStructure, String modelUsed, String fallbackReason,
                         IntervalService.Intervals intervals) {
    }

    /** @param draws number of regime draws for an interval (null = the default); only read when {@code withIntervals} */
    public record IntervalOptions(boolean withIntervals, Integer draws) {
        public static final IntervalOptions NONE = new IntervalOptions(false, null);
    }

    private final TermStructureEngine engine;
    private final MultiStateEngine multiState;
    private final DelinquencyRouter router;
    private final IntervalService intervals;
    private final SurvivalModel survival;

    public RiskProjectionService(TermStructureEngine engine, MultiStateEngine multiState, DelinquencyRouter router,
                                 IntervalService intervals, SurvivalModel survival) {
        this.engine = engine;
        this.multiState = multiState;
        this.router = router;
        this.intervals = intervals;
        this.survival = survival;
    }

    public DelinquencyRouter.Route route(Integer daysPastDue, DelinquencyHistory history) {
        return router.route(daysPastDue, history);
    }

    public String modelUsed(DelinquencyRouter.Route route) {
        return router.modelUsed(route);
    }

    public Result project(TermStructureEngine.Request base, int displayMonths, DelinquencyHistory history, IntervalOptions options) {
        DelinquencyRouter.Route route = router.route(base.daysPastDue(), history);
        String key = key(base, route);
        if (route.useDelinquencyModel()) {
            MultiStateEngine.Request request = new MultiStateEngine.Request(base, route.startState(), route.history());
            MultiStateEngine.Outcome outcome = multiState.projectAll(List.of(request)).get(0);
            TermStructureEngine.TermStructure view = multiState.view(outcome, displayMonths);
            IntervalService.Intervals ranges = null;
            if (options.withIntervals()) {
                MultiStateEngine.Prepared prepared = outcome.prepared();
                ranges = intervals.compute(key, prepared.projection.origin, figuresOf(outcome.summary(), outcome.ecl()),
                        (chain, initial) -> multiState.figures(prepared.projection.main, base.loan().originalInterestRate(),
                                StateRecursion.run(chain, initial, route.startState(), DelinquencyModel.STATES,
                                        prepared.projection.main.forced, prepared.projection.main.rowOffset,
                                        outcome.probabilities(), outcome.firstRow())),
                        options.draws());
            }
            return new Result(view, router.modelUsed(route), null, ranges);
        }
        TermStructureEngine.Outcome outcome = engine.projectAll(List.of(base)).get(0);
        TermStructureEngine.TermStructure view = engine.view(outcome, displayMonths);
        IntervalService.Intervals ranges = null;
        if (options.withIntervals()) {
            ranges = survivalIntervals(base, outcome, key, options.draws());
        }
        return new Result(view, router.modelUsed(route), route.reason(), ranges);
    }

    private IntervalService.Intervals survivalIntervals(TermStructureEngine.Request base, TermStructureEngine.Outcome outcome,
                                                       String key, Integer draws) {
        TermStructureEngine.Projection projection = engine.prepare(base);
        float[] flat = new float[projection.rows() * engine.features()];
        projection.writeRows(flat, 0);
        double[] hazards = engine.hazards(flat, projection.rows());
        // the survival model's (continue, default, prepay) as a one-state transition model
        double[] p = new double[projection.rows() * 3];
        for (int j = 0; j < projection.rows(); j++) {
            p[3 * j] = 1.0 - hazards[2 * j] - hazards[2 * j + 1];
            p[3 * j + 1] = hazards[2 * j];
            p[3 * j + 2] = hazards[2 * j + 1];
        }
        TermStructureEngine.Plan main = projection.main;
        return intervals.compute(key, projection.origin, figuresOf(outcome.summary(), outcome.ecl()),
                (chain, initial) -> multiState.figures(main, base.loan().originalInterestRate(),
                        StateRecursion.run(chain, initial, 0, 1, main.forced, main.rowOffset, p, 0)),
                draws);
    }

    private static MultiStateEngine.Figures figuresOf(TermStructureEngine.Summary s, TermStructureEngine.Ecl e) {
        return new MultiStateEngine.Figures(s.pd12m(), s.pd24m(), s.pdLifetime(), e.ecl12m(), e.eclLifetime(), s.expectedLifeMonths(), 0, null);
    }

    /**
     * What a draw's figures depend on, as a canonical string: the request (a record, so its text is stable), the route and
     * both model versions. Equal requests give equal strings, on any replica, and so equal draws.
     */
    private String key(TermStructureEngine.Request base, DelinquencyRouter.Route route) {
        return base + "|" + route + "|" + router.modelUsed(route) + "|" + survival.version();
    }

    /** The month the regime distribution is taken at, for callers that need it. */
    public YearMonth origin() {
        return engine.forecastOrigin();
    }
}
