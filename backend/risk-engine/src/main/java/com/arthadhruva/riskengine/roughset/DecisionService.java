package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judges a loan's stressed risk against a threshold over a granule of scenario variants and reports which
 * of three regions it falls in. An overlay on {@link TermStructureEngine}: it calls the engine through
 * its public batch method and changes nothing in it, so the validated model, its card checksum and its
 * golden test are not touched by anything here.
 */
@Service
public class DecisionService {

    public static final String METHOD_PERTURBATION = "rough-set three-way decision over a seeded perturbation granule; "
            + "not a probability and not a coverage-guaranteed interval";
    public static final String METHOD_SCENARIO_SET = "rough-set three-way decision over a set of scenarios; "
            + "robustness across the listed scenarios, not a probability and not a coverage-guaranteed interval";

    /**
     * @param baseMetric    the metric of member 0: the unperturbed target scenario (or the first listed scenario)
     * @param extrapolated  some variant pushes a driver the scenario moves outside the range the model was trained
     *                      on, where a tree model's response is held at the edge; the region is unchanged but a
     *                      narrow boundary should not be read as certainty (see {@link #stressDriven})
     * @param extrapolations the drivers, one entry per driver and direction, with the most extreme value seen
     * @param scenarios     the scenario names (scenario set) or empty (perturbation)
     * @param seed          the seed the variants were drawn from (0 and unused for a scenario set)
     * @param exposure      the loan's exposure at default under member 0
     * @param eclBase       lifetime expected credit loss under member 0, in currency
     * @param eclMin        lowest lifetime expected credit loss across the variants
     * @param eclMax        highest lifetime expected credit loss across the variants
     */
    public record Decision(Region region, RoughSetMetric metric, double threshold, double tolerance, int high, int members,
                           double min, double median, double max, double width, double baseMetric, boolean extrapolated,
                           List<TermStructureEngine.Extrapolation> extrapolations, GranuleKind granuleKind, double shockBand,
                           int timingBandMonths, List<String> scenarios, long seed, String method, double exposure,
                           double eclBase, double eclMin, double eclMax) {
    }

    private final TermStructureEngine engine;

    public DecisionService(TermStructureEngine engine) {
        this.engine = engine;
    }

    /**
     * @param base    the loan and context to project; its scenario is the target of a perturbation granule
     *                (null means BASELINE) and is ignored for a scenario set
     * @param options validated options from {@link DecisionOptions#resolve}
     */
    public Decision decide(TermStructureEngine.Request base, DecisionOptions.Resolved options) {
        Scenario target = base.scenario() == null ? Scenario.BASELINE : base.scenario();
        long seed = options.seed() != null ? options.seed() : derivedSeed(base, target, options);
        List<Scenario> members = GranuleBuilder.build(target, options, seed);
        List<TermStructureEngine.Request> requests = new ArrayList<>(members.size());
        for (Scenario member : members) {
            requests.add(new TermStructureEngine.Request(base.loan(), member, base.monthsOnBook(), base.currentBalance(),
                    base.daysPastDue(), base.originationPd12m()));
        }
        List<TermStructureEngine.Outcome> outcomes = engine.projectAll(requests);
        double[] metrics = new double[outcomes.size()];
        double eclMin = Double.POSITIVE_INFINITY;
        double eclMax = Double.NEGATIVE_INFINITY;
        Map<String, TermStructureEngine.Extrapolation> worst = new LinkedHashMap<>();
        for (int i = 0; i < outcomes.size(); i++) {
            metrics[i] = options.metric().of(outcomes.get(i));
            double ecl = outcomes.get(i).ecl().eclLifetime();
            eclMin = Math.min(eclMin, ecl);
            eclMax = Math.max(eclMax, ecl);
            for (TermStructureEngine.Extrapolation e : stressDriven(engine.extrapolations(outcomes.get(i)))) {
                worst.merge(e.driver() + "/" + e.direction(), e, DecisionService::moreExtreme);
            }
        }
        RegionClassifier.Classification c = RegionClassifier.classify(metrics, options.threshold(), options.tolerance());
        boolean set = options.kind() == GranuleKind.SCENARIO_SET;
        return new Decision(c.region(), options.metric(), options.threshold(), options.tolerance(), c.high(), c.members(),
                c.min(), c.median(), c.max(), c.width(), metrics[0], !worst.isEmpty(), List.copyOf(worst.values()), options.kind(),
                options.shockBand(), options.timingBandMonths(), options.scenarios(), set ? 0L : seed,
                set ? METHOD_SCENARIO_SET : METHOD_PERTURBATION, outcomes.get(0).ecl().exposure(),
                outcomes.get(0).ecl().eclLifetime(), eclMin, eclMax);
    }

    /**
     * The extrapolations a scenario can cause. Every long-dated loan runs past the model's training
     * window in {@code loan_age}, and amortisation takes {@code mtm_ltv} below its trained floor, in every
     * scenario including the baseline; flagging those would make the flag always true and say nothing about
     * the stress. What remains are the drivers a scenario moves: unemployment, house prices, the rate
     * incentive, and a mark-to-market LTV pushed above its trained ceiling by falling house prices.
     */
    static List<TermStructureEngine.Extrapolation> stressDriven(List<TermStructureEngine.Extrapolation> all) {
        return all.stream().filter(e -> switch (e.driver()) {
            case "unemployment", "unemployment_change_12m", "hpi_change_12m", "rate_incentive" -> true;
            case "mtm_ltv" -> "above".equals(e.direction());
            default -> false;
        }).toList();
    }

    private static TermStructureEngine.Extrapolation moreExtreme(TermStructureEngine.Extrapolation a, TermStructureEngine.Extrapolation b) {
        return Math.abs(b.extreme() - b.trainedLimit()) > Math.abs(a.extreme() - a.trainedLimit()) ? b : a;
    }

    /**
     * A stable hash of everything that defines the request, so the same request draws the same variants. Cut to 52
     * bits: the seed is returned in the response, and a JSON number above 2^53 is rounded by JavaScript, so a
     * wider seed would be shown wrongly and could not be pasted back to reproduce the result.
     */
    static long derivedSeed(TermStructureEngine.Request base, Scenario target, DecisionOptions.Resolved o) {
        String canonical = base.loan() + "|" + target + "|" + base.monthsOnBook() + "|" + base.currentBalance() + "|"
                + base.daysPastDue() + "|" + base.originationPd12m() + "|" + o.metric() + "|" + o.threshold() + "|" + o.tolerance()
                + "|" + o.kind() + "|" + o.members() + "|" + o.shockBand() + "|" + o.timingBandMonths() + "|" + o.scenarios();
        return SplitMix64.fnv1a(canonical) & DecisionOptions.SEED_MASK;
    }
}
