package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Projects a loan month by month under a macro scenario: the probability it is still active, defaults, or
 * prepays in each future month, and the expected credit loss that follows (IFRS 9 and CECL).
 *
 * <p><b>Regime-modulated survival, computed exactly.</b> The monthly hazards depend on the macro regime,
 * which follows a Markov chain. Averaging the hazards over regime probabilities would be wrong, because
 * survival is a product along the path. The engine instead carries {@code a_k(r)}, the joint probability
 * of "still active at the end of month k and in regime r", forward:
 * <pre>
 *   b_k(r')   = sum_r a_{k-1}(r) P[r][r']         (all mass on the stressed regime while a scenario forces it)
 *   default_k = sum_r b_k(r) h_d(k, r)            prepay_k = sum_r b_k(r) h_p(k, r)
 *   a_k(r)    = b_k(r) (1 - h_d(k, r) - h_p(k, r))
 * </pre>
 * That is the exact expectation over all 2^T regime paths in O(T), with no simulation noise.
 *
 * <p><b>Loss.</b> Exposure at default is the scheduled balance at the start of the month, scaled to the
 * reported balance when one is given. Loss given default is the fitted Beta-regression LGD, floored each
 * month by the shortfall the scenario's house-price path implies for a forced sale
 * ({@link #collateralLgd}), so a price shock raises severity as well as frequency. Losses are discounted
 * at the note rate, the effective interest rate of a fixed-rate loan at par.
 *
 * <p><b>Staging (IFRS 9).</b> Stage 3 at 90+ days past due; stage 2 at 30+ days past due or on a
 * significant increase in credit risk: the PD over the next 12 months is at least
 * {@code sicrRelative} times, and {@code sicrAbsolute} above, the PD for the same 12 months as projected
 * when the loan was originated ({@link #referencePlan}). Stage 1 carries 12-month ECL, stage 2 lifetime
 * ECL, stage 3 the loss on the outstanding balance. CECL is lifetime ECL from day one.
 *
 * <p>The engine is split into {@link #prepare} (everything that does not need the model), one batched
 * model call, and {@link #finish}, so a portfolio run can score thousands of loans per call.
 */
@Service
public class TermStructureEngine {

    /** Longest projection: the longest loan term the models accept. */
    public static final int MAX_MONTHS = 480;

    private static final int REFERENCE_MONTHS = 12;

    private final SurvivalModel model;
    private final MarketData market;
    private final LgdPredictor lgdPredictor;
    private final double liquidationHaircut;
    private final double sicrRelative;
    private final double sicrAbsolute;

    public TermStructureEngine(SurvivalModel model, MarketData market, LgdPredictor lgdPredictor,
                               @Value("${survival.lgd.liquidation-haircut:0.15}") double liquidationHaircut,
                               @Value("${survival.sicr.relative-threshold:2.0}") double sicrRelative,
                               @Value("${survival.sicr.absolute-threshold:0.005}") double sicrAbsolute) {
        if (liquidationHaircut < 0 || liquidationHaircut >= 1) {
            throw new IllegalArgumentException("survival.lgd.liquidation-haircut must be in [0, 1)");
        }
        this.model = model;
        this.market = market;
        this.lgdPredictor = lgdPredictor;
        this.liquidationHaircut = liquidationHaircut;
        this.sicrRelative = sicrRelative;
        this.sicrAbsolute = sicrAbsolute;
    }

    /** A loan that cannot be projected (it has matured, or its inputs contradict each other). */
    public static class InvalidProjectionException extends RuntimeException {
        public InvalidProjectionException(String message) {
            super(message);
        }
    }

    /**
     * @param loan             a normalized, validated loan
     * @param scenario         null for the baseline
     * @param monthsOnBook     the loan's age when its origination month is not given (null or 0 = a new loan)
     * @param currentBalance   outstanding principal today; null assumes the scheduled balance
     * @param daysPastDue      current delinquency, for staging (null = current)
     * @param originationPd12m the 12-month PD recorded at origination, for the SICR test; null estimates it
     */
    public record Request(LoanFeatures loan, Scenario scenario, Integer monthsOnBook, Double currentBalance,
                          Integer daysPastDue, Double originationPd12m) {
    }

    public record MonthPoint(String month, int loanAge, double survival, double marginalDefault, double marginalPrepay,
                             double cumulativeDefault, double cumulativePrepay, double defaultHazard, double prepayHazard,
                             double stressedProbability, double exposure, double lgd, double expectedLoss,
                             double discountedExpectedLoss, double unemployment, double housePriceIndex, double mtmLtv) {
    }

    /** @param maturityProbability probability the loan is repaid on schedule, neither defaulting nor prepaying */
    public record Summary(double pd12m, double pd24m, double pdLifetime, double prepayLifetime, double expectedLifeMonths,
                          double maturityProbability) {
    }

    /**
     * @param lgd           the fitted loss given default, before any collateral shortfall
     * @param lgdPeak       the highest LGD along the scenario's house-price path
     * @param referencePd12m the origination-time PD the SICR test compared against (null when not applicable)
     */
    public record Ecl(int stage, String stageReason, double exposure, double lgd, double lgdPeak, double ecl12m,
                      double eclLifetime, double eclIfrs9, double eclCecl, Double referencePd12m) {
    }

    /** A driver the scenario pushes outside the range the model was trained on, where a tree model's
     * response is held at the edge of that range instead of following the path. */
    public record Extrapolation(String driver, String direction, double trainedLimit, double extreme, int months,
                                String firstMonth) {
    }

    public record Assumptions(String forecastOrigin, String rateAsOf, String regimeAsOf, String originationMonth,
                              double rateSpread, double marketRate, double startUnemployment, double defaultOverlay,
                              double prepayOverlay, double liquidationHaircut, double discountRate) {
    }

    public record TermStructure(String scenario, String modelVersion, int monthsOnBook, int remainingMonths,
                                Summary summary, Ecl ecl, List<MonthPoint> months, List<Extrapolation> extrapolations,
                                Assumptions assumptions) {
    }

    /** One contiguous run of projection months and the model rows that evaluate them. */
    static final class Plan {
        final YearMonth pathOrigin;      // month k of the path is pathOrigin + k
        final int firstK;
        final int months;
        final int[] age;
        final boolean[] forced;          // month forced into the stressed regime (one row instead of two)
        final int[] rowOffset;
        int rows;
        final double incentive;
        final double[] unemployment;
        final double[] unemploymentChange;
        final double[] hpiFactor;        // house prices relative to pathOrigin
        final double[] hpiChange;
        final double[] mtmLtv;
        final double[] exposure;
        final double[] lgd;
        final float[] template;

        Plan(YearMonth pathOrigin, int firstK, int months, float[] template, double incentive) {
            this.pathOrigin = pathOrigin;
            this.firstK = firstK;
            this.months = months;
            this.template = template;
            this.incentive = incentive;
            this.age = new int[months];
            this.forced = new boolean[months];
            this.rowOffset = new int[months];
            this.unemployment = new double[months];
            this.unemploymentChange = new double[months];
            this.hpiFactor = new double[months];
            this.hpiChange = new double[months];
            this.mtmLtv = new double[months];
            this.exposure = new double[months];
            this.lgd = new double[months];
        }
    }

    /** A prepared projection: the scenario path, plus (for a seasoned loan) the origination-time reference. */
    public final class Projection {
        final Request request;
        final Scenario scenario;
        final YearMonth origin;
        final YearMonth origination;
        final int monthsOnBook;
        final double rateSpread;
        final double marketRate;
        final double fittedLgd;
        final double startUnemployment;
        final Plan main;
        final Plan reference;            // null when the SICR test needs no estimate

        private Projection(Request request, Scenario scenario, YearMonth origin, YearMonth origination, int monthsOnBook,
                           double rateSpread, double marketRate, double fittedLgd, double startUnemployment,
                           Plan main, Plan reference) {
            this.request = request;
            this.scenario = scenario;
            this.origin = origin;
            this.origination = origination;
            this.monthsOnBook = monthsOnBook;
            this.rateSpread = rateSpread;
            this.marketRate = marketRate;
            this.fittedLgd = fittedLgd;
            this.startUnemployment = startUnemployment;
            this.main = main;
            this.reference = reference;
        }

        /** Model rows this projection needs. */
        public int rows() {
            return main.rows + (reference == null ? 0 : reference.rows);
        }

        public int months() {
            return main.months;
        }

        /** Writes this projection's rows into {@code flat}, starting at row {@code firstRow}. */
        public void writeRows(float[] flat, int firstRow) {
            write(main, flat, firstRow);
            if (reference != null) {
                write(reference, flat, firstRow + main.rows);
            }
        }
    }

    /** The numbers of a finished projection, as primitive arrays indexed by month (0 = the first projected). */
    public static final class Outcome {
        final Projection projection;
        final double[] survival;
        final double[] marginalDefault;
        final double[] marginalPrepay;
        final double[] stressed;
        final double[] defaultHazard;
        final double[] prepayHazard;
        final double[] discountedLoss;
        final Summary summary;
        final Ecl ecl;

        Outcome(Projection projection, Curve curve, double[] discountedLoss, Summary summary, Ecl ecl) {
            this.projection = projection;
            this.survival = curve.survival;
            this.marginalDefault = curve.marginalDefault;
            this.marginalPrepay = curve.marginalPrepay;
            this.stressed = curve.stressed;
            this.defaultHazard = curve.defaultHazard;
            this.prepayHazard = curve.prepayHazard;
            this.discountedLoss = discountedLoss;
            this.summary = summary;
            this.ecl = ecl;
        }

        public Summary summary() {
            return summary;
        }

        public Ecl ecl() {
            return ecl;
        }

        /** Months projected: the loan's remaining life. */
        public int months() {
            return survival.length;
        }

        public int monthsOnBook() {
            return projection.monthsOnBook;
        }

        public double[] survival(int months) {
            return Arrays.copyOf(survival, months);
        }

        public double[] discountedLoss(int months) {
            return Arrays.copyOf(discountedLoss, months);
        }

        public double[] cumulativeDefault(int months) {
            return runningSum(marginalDefault, months);
        }

        public double[] cumulativePrepay(int months) {
            return runningSum(marginalPrepay, months);
        }

        /** Calendar month ({@code YYYY-MM}) of each of the first {@code months} projected months. */
        public List<String> monthLabels(int months) {
            List<String> labels = new ArrayList<>(months);
            for (int i = 0; i < months; i++) {
                labels.add(projection.origin.plusMonths(projection.main.firstK + i).toString());
            }
            return labels;
        }

        private static double[] runningSum(double[] values, int months) {
            double[] out = new double[months];
            double sum = 0;
            for (int i = 0; i < months; i++) {
                sum += values[i];
                out[i] = sum;
            }
            return out;
        }
    }

    private static final class Curve {
        final double[] survival;
        final double[] marginalDefault;
        final double[] marginalPrepay;
        final double[] stressed;
        final double[] defaultHazard;
        final double[] prepayHazard;
        double cumulativeDefault;
        double cumulativePrepay;

        Curve(int months) {
            survival = new double[months];
            marginalDefault = new double[months];
            marginalPrepay = new double[months];
            stressed = new double[months];
            defaultHazard = new double[months];
            prepayHazard = new double[months];
        }
    }

    /** Full single-loan projection: one batched model call. */
    public TermStructure project(Request request, int displayMonths) {
        return view(projectAll(List.of(request)).get(0), displayMonths);
    }

    /** Projects every request in one batched model call. */
    public List<Outcome> projectAll(List<Request> requests) {
        List<Projection> projections = new ArrayList<>(requests.size());
        int rows = 0;
        for (Request request : requests) {
            Projection projection = prepare(request);
            projections.add(projection);
            rows += projection.rows();
        }
        float[] flat = new float[rows * model.features()];
        int[] firstRow = new int[projections.size()];
        int at = 0;
        for (int i = 0; i < projections.size(); i++) {
            firstRow[i] = at;
            projections.get(i).writeRows(flat, at);
            at += projections.get(i).rows();
        }
        double[] hazards = model.hazards(flat, rows);
        List<Outcome> outcomes = new ArrayList<>(projections.size());
        for (int i = 0; i < projections.size(); i++) {
            outcomes.add(finish(projections.get(i), hazards, firstRow[i]));
        }
        return outcomes;
    }

    /** Hazards for rows written by {@link Projection#writeRows}. */
    public double[] hazards(float[] flat, int rows) {
        return model.hazards(flat, rows);
    }

    public int features() {
        return model.features();
    }

    /** The month projections start after: the latest month of observed macro data. */
    public YearMonth forecastOrigin() {
        return market.macroAsOf();
    }

    public Projection prepare(Request request) {
        LoanFeatures loan = request.loan();
        Scenario scenario = request.scenario() == null ? Scenario.BASELINE : request.scenario();
        YearMonth origin = market.macroAsOf();
        boolean newLoan = loan.originationMonth() == null && (request.monthsOnBook() == null || request.monthsOnBook() == 0);
        YearMonth origination;
        if (loan.originationMonth() != null) {
            if (request.monthsOnBook() != null) {
                throw new InvalidProjectionException("Give the loan's originationMonth or monthsOnBook, not both");
            }
            origination = YearMonth.parse(loan.originationMonth());
        } else {
            origination = newLoan ? origin : origin.minusMonths(request.monthsOnBook());
        }
        // A loan originated after the latest macro month is projected as new from that month.
        int monthsOnBook = (int) Math.max(0, origination.until(origin, ChronoUnit.MONTHS));
        int term = loan.originalLoanTerm();
        if (monthsOnBook >= term) {
            throw new InvalidProjectionException("The loan has reached maturity (" + monthsOnBook + " of " + term + " months)");
        }
        int months = Math.min(term - monthsOnBook, MAX_MONTHS);

        double noteRate = loan.originalInterestRate();
        YearMonth lock = newLoan ? market.rateAsOf() : origination.minusMonths(model.rateLockLagMonths());
        double rateSpread = noteRate - market.mortgageRate(lock);
        float[] template = model.template(loan, rateSpread);
        String state = loan.propertyState();
        double hpiOrigination = market.hpi(state, origination);
        double scheduledNow = loan.originalUpb() * amortizationFactor(noteRate, term, monthsOnBook);
        double balanceScale = request.currentBalance() == null ? 1.0 : request.currentBalance() / scheduledNow;
        double fittedLgd = Math.max(0.0, Math.min(1.0, lgdPredictor.predict(loan)));
        double marketRate = market.latestMortgageRate() + scenario.rateShockBp() / 100.0;

        Plan main = fill(new Plan(origin, 1, months, template, noteRate - marketRate), loan, scenario, monthsOnBook,
                hpiOrigination, balanceScale, fittedLgd);
        Plan reference = null;
        boolean performing = request.daysPastDue() == null || request.daysPastDue() < 30;
        if (performing && monthsOnBook > 0 && request.originationPd12m() == null) {
            reference = referencePlan(loan, template, origination, monthsOnBook, Math.min(REFERENCE_MONTHS, months), fittedLgd);
        }
        return new Projection(request, scenario, origin, origination, monthsOnBook, rateSpread, marketRate, fittedLgd,
                market.unemployment(state, origin), main, reference);
    }

    /**
     * The same next-12-month window as the scenario path, but as the baseline projected it on the day the
     * loan was originated: macro history up to origination and the baseline after it, the market rate of
     * that month, and the regime outlook of that month. Comparing like for like (same loan ages, same
     * calendar months) keeps ordinary seasoning from registering as an increase in credit risk.
     */
    private Plan referencePlan(LoanFeatures loan, float[] template, YearMonth origination, int monthsOnBook, int months,
                               double fittedLgd) {
        double incentive = loan.originalInterestRate() - market.mortgageRate(origination);
        return fill(new Plan(origination, monthsOnBook + 1, months, template, incentive), loan, Scenario.BASELINE, 0,
                market.hpi(loan.propertyState(), origination), 1.0, fittedLgd);
    }

    private Plan fill(Plan p, LoanFeatures loan, Scenario scenario, int ageAtPathOrigin, double hpiOrigination,
                      double balanceScale, double fittedLgd) {
        String state = loan.propertyState();
        double noteRate = loan.originalInterestRate();
        int term = loan.originalLoanTerm();
        double unemploymentStart = market.unemployment(state, p.pathOrigin);
        double hpiStart = market.hpi(state, p.pathOrigin);
        int offset = 0;
        for (int i = 0; i < p.months; i++) {
            int k = p.firstK + i;
            p.age[i] = ageAtPathOrigin + k;
            p.forced[i] = k <= scenario.stressedMonths();
            p.rowOffset[i] = offset;
            offset += p.forced[i] ? 1 : 2;

            p.unemployment[i] = unemploymentStart + scenario.unemploymentDelta(k);
            double unemploymentYearAgo = k <= 12 ? market.unemployment(state, p.pathOrigin.plusMonths(k - 12))
                    : unemploymentStart + scenario.unemploymentDelta(k - 12);
            p.unemploymentChange[i] = p.unemployment[i] - unemploymentYearAgo;

            p.hpiFactor[i] = scenario.hpiFactor(k);
            double hpi = hpiStart * p.hpiFactor[i];
            double hpiYearAgo = k <= 12 ? market.hpi(state, p.pathOrigin.plusMonths(k - 12)) : hpiStart * scenario.hpiFactor(k - 12);
            p.hpiChange[i] = hpi / hpiYearAgo - 1.0;

            double valueRatio = hpiOrigination / hpi;      // original property value over its value this month
            p.mtmLtv[i] = loan.originalLtv() * amortizationFactor(noteRate, term, p.age[i]) * valueRatio;
            double balanceFraction = amortizationFactor(noteRate, term, p.age[i] - 1) * balanceScale;
            p.exposure[i] = loan.originalUpb() * balanceFraction;
            p.lgd[i] = collateralLgd(fittedLgd, loan.originalLtv() * balanceFraction * valueRatio, loan.miPercent());
        }
        p.rows = offset;
        return p;
    }

    private void write(Plan p, float[] flat, int firstRow) {
        int width = model.features();
        for (int i = 0; i < p.months; i++) {
            int at = (firstRow + p.rowOffset[i]) * width;
            if (p.forced[i]) {
                model.writeRow(flat, at, p.template, p.age[i], SurvivalModel.STRESSED, p.incentive, p.unemployment[i],
                        p.unemploymentChange[i], p.hpiChange[i], p.mtmLtv[i]);
            } else {
                model.writeRow(flat, at, p.template, p.age[i], SurvivalModel.CALM, p.incentive, p.unemployment[i],
                        p.unemploymentChange[i], p.hpiChange[i], p.mtmLtv[i]);
                model.writeRow(flat, at + width, p.template, p.age[i], SurvivalModel.STRESSED, p.incentive, p.unemployment[i],
                        p.unemploymentChange[i], p.hpiChange[i], p.mtmLtv[i]);
            }
        }
    }

    /**
     * Loss given default with the collateral re-marked along the scenario path. A forced sale recovers
     * the property's value less the liquidation haircut (distressed-sale discount plus foreclosure,
     * carrying and selling costs); mortgage insurance absorbs the first {@code miPercent} of the claim.
     * The fitted LGD is the floor: it already reflects the costs of defaults with ample equity.
     *
     * @param ltvAtDefault outstanding balance over the property's value that month, in percent
     */
    double collateralLgd(double fittedLgd, double ltvAtDefault, double miPercent) {
        double shortfall = 1.0 - (1.0 - liquidationHaircut) * 100.0 / ltvAtDefault - miPercent / 100.0;
        return Math.min(1.0, Math.max(fittedLgd, shortfall));
    }

    /** Runs the regime recursion over hazards laid out by {@link Projection#writeRows} from {@code firstRow}. */
    public Outcome finish(Projection projection, double[] hazards, int firstRow) {
        Plan main = projection.main;
        Curve curve = recurse(main, hazards, firstRow, model.regimeDistribution(projection.origin, projection.origin));
        LoanFeatures loan = projection.request.loan();

        int months = main.months;
        double[] discountedLoss = new double[months];
        double monthlyDiscount = 1.0 / (1.0 + loan.originalInterestRate() / 1200.0);
        double discount = 1.0, ecl12 = 0, eclLifetime = 0, expectedLife = 0, cumulative = 0, pd12 = 0, pd24 = 0, lgdPeak = 0;
        for (int i = 0; i < months; i++) {
            discount *= monthlyDiscount;
            discountedLoss[i] = curve.marginalDefault[i] * main.lgd[i] * main.exposure[i] * discount;
            eclLifetime += discountedLoss[i];
            cumulative += curve.marginalDefault[i];
            if (i < 12) {
                ecl12 += discountedLoss[i];
                pd12 = cumulative;
            }
            if (i < 24) {
                pd24 = cumulative;
            }
            expectedLife += i == 0 ? 1.0 : curve.survival[i - 1];
            lgdPeak = Math.max(lgdPeak, main.lgd[i]);
        }
        Summary summary = new Summary(pd12, pd24, curve.cumulativeDefault, curve.cumulativePrepay, expectedLife,
                curve.survival[months - 1]);

        Double referencePd = projection.request.originationPd12m();
        if (projection.reference != null) {
            Curve reference = recurse(projection.reference, hazards, firstRow + main.rows,
                    model.regimeDistribution(projection.origin, projection.origination));
            referencePd = reference.cumulativeDefault;
        }
        int daysPastDue = projection.request.daysPastDue() == null ? 0 : projection.request.daysPastDue();
        int stage;
        String reason;
        if (daysPastDue >= 90) {
            stage = 3;
            reason = "90 or more days past due: credit-impaired";
        } else if (daysPastDue >= 30) {
            stage = 2;
            reason = "30 or more days past due";
        } else if (referencePd != null && pd12 >= sicrRelative * referencePd && pd12 - referencePd >= sicrAbsolute) {
            stage = 2;
            reason = "Significant increase in credit risk since origination";
        } else {
            stage = 1;
            reason = projection.monthsOnBook == 0 ? "Newly originated" : "No significant increase in credit risk since origination";
        }
        double exposure = main.exposure[0];
        double impaired = main.lgd[0] * exposure;
        double ifrs9 = stage == 1 ? ecl12 : stage == 2 ? eclLifetime : impaired;
        double cecl = stage == 3 ? impaired : eclLifetime;
        Ecl ecl = new Ecl(stage, reason, exposure, projection.fittedLgd, lgdPeak, ecl12, eclLifetime, ifrs9, cecl, referencePd);
        return new Outcome(projection, curve, discountedLoss, summary, ecl);
    }

    private Curve recurse(Plan p, double[] hazards, int firstRow, double[] initialRegime) {
        double[][] transition = model.transitionMatrix();
        double calmToCalm = transition[SurvivalModel.CALM][SurvivalModel.CALM];
        double calmToStressed = transition[SurvivalModel.CALM][SurvivalModel.STRESSED];
        double stressedToCalm = transition[SurvivalModel.STRESSED][SurvivalModel.CALM];
        double stressedToStressed = transition[SurvivalModel.STRESSED][SurvivalModel.STRESSED];
        Curve curve = new Curve(p.months);
        double aliveCalm = initialRegime[SurvivalModel.CALM];
        double aliveStressed = initialRegime[SurvivalModel.STRESSED];
        for (int i = 0; i < p.months; i++) {
            int row = 2 * (firstRow + p.rowOffset[i]);
            double inCalm, inStressed, defaults, prepays;
            if (p.forced[i]) {
                inCalm = 0;
                inStressed = aliveCalm + aliveStressed;
                double hd = hazards[row], hp = hazards[row + 1];
                defaults = inStressed * hd;
                prepays = inStressed * hp;
                aliveCalm = 0;
                aliveStressed = inStressed * (1 - hd - hp);
            } else {
                inCalm = aliveCalm * calmToCalm + aliveStressed * stressedToCalm;
                inStressed = aliveCalm * calmToStressed + aliveStressed * stressedToStressed;
                double hdCalm = hazards[row], hpCalm = hazards[row + 1];
                double hdStressed = hazards[row + 2], hpStressed = hazards[row + 3];
                defaults = inCalm * hdCalm + inStressed * hdStressed;
                prepays = inCalm * hpCalm + inStressed * hpStressed;
                aliveCalm = inCalm * (1 - hdCalm - hpCalm);
                aliveStressed = inStressed * (1 - hdStressed - hpStressed);
            }
            double atRisk = inCalm + inStressed;
            curve.marginalDefault[i] = defaults;
            curve.marginalPrepay[i] = prepays;
            curve.survival[i] = aliveCalm + aliveStressed;
            curve.stressed[i] = atRisk > 0 ? inStressed / atRisk : 0;
            curve.defaultHazard[i] = atRisk > 0 ? defaults / atRisk : 0;
            curve.prepayHazard[i] = atRisk > 0 ? prepays / atRisk : 0;
            curve.cumulativeDefault += defaults;
            curve.cumulativePrepay += prepays;
        }
        return curve;
    }

    /** The API view of an outcome, with the monthly series cut to {@code displayMonths}. */
    public TermStructure view(Outcome o, int displayMonths) {
        Projection projection = o.projection;
        Plan p = projection.main;
        LoanFeatures loan = projection.request.loan();
        int shown = Math.max(1, Math.min(displayMonths, p.months));
        List<MonthPoint> points = new ArrayList<>(shown);
        double discount = 1.0, monthlyDiscount = 1.0 / (1.0 + loan.originalInterestRate() / 1200.0);
        double cumulativeDefault = 0, cumulativePrepay = 0;
        for (int i = 0; i < shown; i++) {
            discount *= monthlyDiscount;
            cumulativeDefault += o.marginalDefault[i];
            cumulativePrepay += o.marginalPrepay[i];
            points.add(new MonthPoint(projection.origin.plusMonths(p.firstK + i).toString(), p.age[i], o.survival[i],
                    o.marginalDefault[i], o.marginalPrepay[i], cumulativeDefault, cumulativePrepay, o.defaultHazard[i],
                    o.prepayHazard[i], o.stressed[i], p.exposure[i], p.lgd[i], o.discountedLoss[i] / discount,
                    o.discountedLoss[i], p.unemployment[i], 100.0 * p.hpiFactor[i], p.mtmLtv[i]));
        }
        Assumptions assumptions = new Assumptions(projection.origin.toString(), market.rateAsOf().toString(),
                model.regimeAsOf().toString(), projection.origination.toString(), projection.rateSpread, projection.marketRate,
                projection.startUnemployment, model.defaultScalar(), model.prepayScalar(), liquidationHaircut,
                loan.originalInterestRate());
        return new TermStructure(projection.scenario.name(), model.version(), projection.monthsOnBook, p.months,
                o.summary, o.ecl, points, extrapolations(o), assumptions);
    }

    /** Where the scenario path leaves the range the model was trained on. */
    public List<Extrapolation> extrapolations(Outcome outcome) {
        Projection projection = outcome.projection;
        Plan p = projection.main;
        List<Extrapolation> found = new ArrayList<>();
        double[] age = new double[p.months];
        double[] incentive = new double[p.months];
        for (int i = 0; i < p.months; i++) {
            age[i] = p.age[i];
            incentive[i] = p.incentive;
        }
        outside(found, projection, "loan_age", age);
        outside(found, projection, "rate_incentive", incentive);
        outside(found, projection, "unemployment", p.unemployment);
        outside(found, projection, "unemployment_change_12m", p.unemploymentChange);
        outside(found, projection, "hpi_change_12m", p.hpiChange);
        outside(found, projection, "mtm_ltv", p.mtmLtv);
        return found;
    }

    private void outside(List<Extrapolation> found, Projection projection, String driver, double[] path) {
        SurvivalModel.Range range = model.envelope(driver);
        int below = 0, above = 0, firstBelow = -1, firstAbove = -1;
        double lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < path.length; i++) {
            if (path[i] < range.min()) {
                below++;
                lowest = Math.min(lowest, path[i]);
                firstBelow = firstBelow < 0 ? i : firstBelow;
            } else if (path[i] > range.max()) {
                above++;
                highest = Math.max(highest, path[i]);
                firstAbove = firstAbove < 0 ? i : firstAbove;
            }
        }
        if (below > 0) {
            found.add(new Extrapolation(driver, "below", range.min(), lowest, below,
                    projection.origin.plusMonths(projection.main.firstK + firstBelow).toString()));
        }
        if (above > 0) {
            found.add(new Extrapolation(driver, "above", range.max(), highest, above,
                    projection.origin.plusMonths(projection.main.firstK + firstAbove).toString()));
        }
    }

    /** Scheduled balance as a fraction of the original after {@code payments} level payments. */
    static double amortizationFactor(double ratePct, int term, int payments) {
        double a = Math.max(0, Math.min(payments, term));
        double r = ratePct / 1200.0;
        if (r <= 0) {
            return 1.0 - a / term;
        }
        double growN = Math.pow(1 + r, term);
        return (growN - Math.pow(1 + r, a)) / (growN - 1);
    }
}
