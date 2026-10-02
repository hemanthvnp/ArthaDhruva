package com.arthadhruva.riskengine.ml;

/**
 * The standard normal distribution function and its inverse, to double precision. The JDK has neither,
 * and the portfolio loss model needs both in the far tail (a 99.9% quantile, a PD of a few basis points),
 * where the short textbook approximations lose most of their digits.
 */
public final class Normal {

    private static final double SQRT_TWO_PI = 2.5066282746310002;
    private static final double CONTINUED_FRACTION_FROM = 4.0;
    private static final int CONTINUED_FRACTION_TERMS = 32;

    private Normal() {
    }

    /**
     * P(Z <= x), with relative error below 2e-13 over the whole line, tails included. Up to |x| = 4 it is
     * Hart's rational approximation (as given by West, 2005); that one is accurate in absolute terms only,
     * and by |x| = 7 has lost half its digits relative to the tiny tail probability, so beyond 4 the tail
     * comes from Laplace's continued fraction, which converges faster the further out it is evaluated.
     */
    public static double cdf(double x) {
        double a = Math.abs(x);
        double upperTail;
        if (a > 38.5) {
            upperTail = 0;
        } else if (a < CONTINUED_FRACTION_FROM) {
            double numerator = 3.52624965998911E-02 * a + 0.700383064443688;
            numerator = numerator * a + 6.37396220353165;
            numerator = numerator * a + 33.912866078383;
            numerator = numerator * a + 112.079291497871;
            numerator = numerator * a + 221.213596169931;
            numerator = numerator * a + 220.206867912376;
            double denominator = 8.83883476483184E-02 * a + 1.75566716318264;
            denominator = denominator * a + 16.064177579207;
            denominator = denominator * a + 86.7807322029461;
            denominator = denominator * a + 296.564248779674;
            denominator = denominator * a + 637.333633378831;
            denominator = denominator * a + 793.826512519948;
            denominator = denominator * a + 440.413735824752;
            upperTail = Math.exp(-a * a / 2) * numerator / denominator;
        } else {
            // Q(a) = pdf(a) / (a + 1 / (a + 2 / (a + 3 / ...)))
            double fraction = a;
            for (int k = CONTINUED_FRACTION_TERMS; k >= 1; k--) {
                fraction = a + k / fraction;
            }
            upperTail = Math.exp(-a * a / 2) / fraction / SQRT_TWO_PI;
        }
        return x > 0 ? 1 - upperTail : upperTail;
    }

    /** The standard normal density. */
    public static double pdf(double x) {
        return Math.exp(-x * x / 2) / SQRT_TWO_PI;
    }

    /**
     * The x with P(Z <= x) = p: Acklam's rational approximation (relative error 1e-9) polished by one
     * Halley step against {@link #cdf}, which brings it to the precision of the cdf itself. The upper half
     * is computed through the lower one, since 1 - p is exact there and p's own digits run out near 1.
     *
     * @return negative infinity for p <= 0, positive infinity for p >= 1
     */
    public static double inverseCdf(double p) {
        if (Double.isNaN(p)) {
            return Double.NaN;
        }
        if (p <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        if (p >= 1) {
            return Double.POSITIVE_INFINITY;
        }
        return p > 0.5 ? -lowerHalf(1 - p) : lowerHalf(p);
    }

    private static double lowerHalf(double p) {
        double x;
        if (p < 0.02425) {
            double q = Math.sqrt(-2 * Math.log(p));
            x = (((((-7.784894002430293e-03 * q - 3.223964580411365e-01) * q - 2.400758277161838e+00) * q
                    - 2.549732539343734e+00) * q + 4.374664141464968e+00) * q + 2.938163982698783e+00)
                    / ((((7.784695709041462e-03 * q + 3.224671290700398e-01) * q + 2.445134137142996e+00) * q
                    + 3.754408661907416e+00) * q + 1);
        } else {
            double q = p - 0.5;
            double r = q * q;
            x = (((((-3.969683028665376e+01 * r + 2.209460984245205e+02) * r - 2.759285104469687e+02) * r
                    + 1.383577518672690e+02) * r - 3.066479806614716e+01) * r + 2.506628277459239e+00) * q
                    / (((((-5.447609879822406e+01 * r + 1.615858368580409e+02) * r - 1.556989798598866e+02) * r
                    + 6.680131188771972e+01) * r - 1.328068155288572e+01) * r + 1);
        }
        double error = cdf(x) - p;
        double u = error * SQRT_TWO_PI * Math.exp(x * x / 2);
        return x - u / (1 + x * u / 2);
    }
}
