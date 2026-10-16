package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.TermStructureEngine.InvalidProjectionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the variants a loan is judged over.
 *
 * <p>A perturbation granule is the neighbourhood of one target scenario: member 0 is the target itself;
 * every other member scales the three shock magnitudes by a factor in {@code [1 - band, 1 + band]} and
 * shifts the timing of the shocks by up to {@code timingBandMonths}, then clamps to the ranges a
 * {@link Scenario} allows. A parameter that is zero in the target stays zero (a baseline has no shock to
 * scale, and an unstressed scenario does not become stressed). Six uniform draws are consumed per member
 * whatever the target, so the draw positions never depend on which parameters are zero.
 */
public final class GranuleBuilder {

    private GranuleBuilder() {
    }

    public static List<Scenario> build(Scenario target, DecisionOptions.Resolved options, long seed) {
        if (options.kind() == GranuleKind.SCENARIO_SET) {
            List<Scenario> members = new ArrayList<>(options.scenarios().size());
            for (String name : options.scenarios()) {
                try {
                    members.add(Scenario.named(name));
                } catch (IllegalArgumentException e) {
                    throw new InvalidProjectionException(e.getMessage());
                }
            }
            return members;
        }
        List<Scenario> members = new ArrayList<>(options.members());
        members.add(target);
        for (int i = 1; i < options.members(); i++) {
            members.add(variant(target, i, options.shockBand(), options.timingBandMonths(), seed));
        }
        return members;
    }

    /** Member {@code index} (at least 1) of the neighbourhood of {@code target}; a pure function of its arguments. */
    static Scenario variant(Scenario target, int index, double band, int timingBand, long seed) {
        SplitMix64 rng = SplitMix64.forMember(seed, index);
        double unemploymentFactor = factor(rng.nextUnit(), band);
        double hpiFactor = factor(rng.nextUnit(), band);
        double rateFactor = factor(rng.nextUnit(), band);
        int unemploymentShift = shift(rng.nextUnit(), timingBand);
        int hpiShift = shift(rng.nextUnit(), timingBand);
        int stressedShift = shift(rng.nextUnit(), timingBand);

        double unemploymentPp = clamp(target.unemploymentShockPp() * unemploymentFactor, -5.0, 15.0);
        double hpiPct = clamp(target.hpiShockPct() * hpiFactor, -30.0, 60.0);
        double rateBp = clamp(target.rateShockBp() * rateFactor, -400.0, 600.0);
        int unemploymentMonths = target.unemploymentShockPp() == 0.0 ? target.unemploymentShockMonths()
                : clamp(target.unemploymentShockMonths() + unemploymentShift, 1, 60);
        int hpiMonths = target.hpiShockPct() == 0.0 ? target.hpiShockMonths()
                : clamp(target.hpiShockMonths() + hpiShift, 1, 60);
        int stressedMonths = target.stressedMonths() == 0 ? 0 : clamp(target.stressedMonths() + stressedShift, 0, 120);

        String name = (target.name().length() > 24 ? target.name().substring(0, 24) : target.name()) + "~" + index;
        return new Scenario(name, "Variant " + index + " of " + target.name(), stressedMonths, unemploymentPp, unemploymentMonths,
                target.unemploymentRecoveryMonths(), hpiPct, hpiMonths, target.hpiAnnualGrowthPct(), rateBp);
    }

    private static double factor(double unit, double band) {
        return 1.0 - band + 2.0 * band * unit;
    }

    /** An integer in [-band, +band], uniform over its 2 * band + 1 values. */
    private static int shift(double unit, int band) {
        return (int) Math.floor(unit * (2 * band + 1)) - band;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
