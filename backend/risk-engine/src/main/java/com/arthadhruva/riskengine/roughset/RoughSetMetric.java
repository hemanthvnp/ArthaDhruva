package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.survival.TermStructureEngine;

/** The stressed risk figure a threshold is applied to. */
public enum RoughSetMetric {
    PD_12M {
        @Override
        public double of(TermStructureEngine.Outcome outcome) {
            return outcome.summary().pd12m();
        }
    },
    PD_LIFETIME {
        @Override
        public double of(TermStructureEngine.Outcome outcome) {
            return outcome.summary().pdLifetime();
        }
    },
    /** Lifetime expected credit loss over exposure: a rate, so loans of different size are comparable. */
    ECL_LIFETIME_RATE {
        @Override
        public double of(TermStructureEngine.Outcome outcome) {
            TermStructureEngine.Ecl ecl = outcome.ecl();
            return ecl.exposure() > 0 ? ecl.eclLifetime() / ecl.exposure() : 0.0;
        }
    };

    public abstract double of(TermStructureEngine.Outcome outcome);
}
