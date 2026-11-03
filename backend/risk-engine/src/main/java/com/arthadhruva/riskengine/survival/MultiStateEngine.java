package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.ml.MarketData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects a loan whose current delinquency status and recent history are known, with the transition model in
 * place of the survival model's hazards: the probability, month by month, that it is active in each of current, 30 and
 * 60 days past due, has defaulted, or has prepaid, and the expected credit loss that follows.
 *
 * <p>It does not reimplement the existing engine: {@link TermStructureEngine#prepare} builds the scenario path, the
 * model rows, the exposure and the loss given default with its collateral floor, and this class evaluates those rows
 * from every active state and runs {@link StateRecursion} over them. Exposure, loss given default and discounting are
 * therefore the existing engine's own figures, and a test holds the two to each other when the state model is given the
 * existing hazards ({@code MultiStateEquivalenceTest}).
 *
 * <p>The loan's history is aged with the projection by a fixed rule ({@link #agedHistory}), because a path-dependent
 * history would make the exact recursion impossible; the model card states the approximation and the 12-month rates by
 * starting state were checked under the same rule.
 */
@Service
public class MultiStateEngine {

    public static final DelinquencyModel.History NO_HISTORY = new DelinquencyModel.History(0, Integer.MAX_VALUE, 0);

    private final TermStructureEngine engine;
    private final SurvivalModel survival;
    private final DelinquencyModel model;
    private final MarketData market;
    private final double liquidationHaircut;
    private final double sicrRelative;
    private final double sicrAbsolute;

    public MultiStateEngine(TermStructureEngine engine, SurvivalModel survival, DelinquencyModel model, MarketData market,
                            @Value("${survival.lgd.liquidation-haircut:0.15}") double liquidationHaircut,
                            @Value("${survival.sicr.relative-threshold:2.0}") double sicrRelative,
                            @Value("${survival.sicr.absolute-threshold:0.005}") double sicrAbsolute) {
        this.engine = engine;
        this.survival = survival;
        this.model = model;
        this.market = market;
        this.liquidationHaircut = liquidationHaircut;
        this.sicrRelative = sicrRelative;
        this.sicrAbsolute = sicrAbsolute;
    }

    /** @param startState 0 current, 1 30 days past due, 2 60 days past due; {@code base.daysPastDue()} is used for staging */
    public record Request(TermStructureEngine.Request base, int startState, DelinquencyModel.History history) {
    }

    /** A prepared projection: the existing engine's, and the history its rows are evaluated with. */
    public final class Prepared {
        final Request request;
        final TermStructureEngine.Projection projection;
        final DelinquencyModel.History history;

        private Prepared(Request request, TermStructureEngine.Projection projection) {
            this.request = request;
            this.projection = projection;
            this.history = new DelinquencyModel.History(request.history().episodes(),
                    Math.min(request.history().monthsSinceCure(), model.noCureMonths()), request.history().maxState());
        }

        /** Model rows: each of the existing engine's rows, evaluated from each active state. */
        public int rows() {
            return projection.rows() * DelinquencyModel.STATES;
        }

        public int months() {
            return projection.months();
        }

        /**
         * Writes this projection's rows into {@code flat}, starting at row {@code firstRow}. Each row is evaluated from
         * every active state, with the loan's history aged to that row's month ({@link MultiStateEngine#agedHistory}).
         */
        public void writeRows(float[] flat, int firstRow) {
            int survivalRows = projection.rows();
            float[] base = new float[survivalRows * engine.features()];
            projection.writeRows(base, 0);
            TermStructureEngine.Plan main = projection.main;
            int[] mainElapsed = elapsed(main);
            DelinquencyModel.History clean = new DelinquencyModel.History(0, model.noCureMonths(), 0);
            int[] referenceElapsed = projection.reference == null ? new int[0] : elapsed(projection.reference);
            for (int j = 0; j < survivalRows; j++) {
                // the reference plan (the SICR comparison) is the same loan as it was at origination: current, no history
                boolean isMain = j < main.rows;
                int months = isMain ? mainElapsed[j] : referenceElapsed[j - main.rows];
                for (int s = 0; s < DelinquencyModel.STATES; s++) {
                    DelinquencyModel.History in = agedHistory(s, months, isMain ? history : clean, model.noCureMonths());
                    model.writeRow(flat, firstRow + j * DelinquencyModel.STATES + s, base, j, s, in);
                }
            }
        }
    }

    /** The figures of a finished projection, as arrays indexed by month (0 = the first projected). */
    public static final class Outcome {
        final Prepared prepared;
        final StateRecursion.Result curve;
        final double[] discountedLoss;
        final TermStructureEngine.Summary summary;
        final TermStructureEngine.Ecl ecl;
        final double[] probabilities;
        final int firstRow;

        Outcome(Prepared prepared, StateRecursion.Result curve, double[] discountedLoss, TermStructureEngine.Summary summary,
                TermStructureEngine.Ecl ecl, double[] probabilities, int firstRow) {
            this.prepared = prepared;
            this.curve = curve;
            this.discountedLoss = discountedLoss;
            this.summary = summary;
            this.ecl = ecl;
            this.probabilities = probabilities;
            this.firstRow = firstRow;
        }

        /** The transition probabilities this outcome was computed from, and where its rows start in them (in the existing
         * engine's rows): enough to rerun the recursion under another regime chain without another model call. */
        double[] probabilities() {
            return probabilities;
        }

        int firstRow() {
            return firstRow;
        }

        public TermStructureEngine.Summary summary() {
            return summary;
        }

        public TermStructureEngine.Ecl ecl() {
            return ecl;
        }

        public int months() {
            return curve.survival().length;
        }

        public StateRecursion.Result curve() {
            return curve;
        }

        public Prepared prepared() {
            return prepared;
        }

        public double[] discountedLoss() {
            return discountedLoss;
        }
    }

    /** The lifetime and 12-month figures of one run of the recursion; what an interval draw reports. */
    record Figures(double pd12m, double pd24m, double pdLifetime, double ecl12m, double eclLifetime, double expectedLife,
                   double lgdPeak, double[] discountedLoss) {
    }

    /** Months elapsed since the start of the plan for each of its model rows (a month has one row while forced, else two). */
    private static int[] elapsed(TermStructureEngine.Plan plan) {
        int[] out = new int[plan.rows];
        for (int i = 0; i < plan.months; i++) {
            int rows = plan.forced[i] ? 1 : 2;
            for (int r = 0; r < rows; r++) {
                out[plan.rowOffset[i] + r] = i;
            }
        }
        return out;
    }

    /**
     * The history a model row is evaluated with: the loan's history at the start of the projection, aged {@code elapsed}
     * months, from {@code source}. It depends on the history and the month and never on the state the loan starts in, so loans
     * that differ only in their starting state are projected with the same transition matrices (which is what lets the
     * monotone repair guarantee their ordering). The rule is defined once, in backend/delinquency_history.py (that file
     * explains it and its limits), and this is the same rule row for row; the golden test holds the two to each other.
     *
     * @param h       the loan's history at the start ({@code monthsSinceCure} is {@code noCure} when there was none)
     */
    static DelinquencyModel.History agedHistory(int source, int elapsed, DelinquencyModel.History h, int noCure) {
        final int window = 12;
        int episodes = h.episodes(), since = h.monthsSinceCure(), worst = h.maxState();
        if (source == DelinquencyModel.CURRENT) {
            int age = since < noCure ? Math.min(since + elapsed, noCure) : (episodes > 0 ? elapsed : noCure);
            return age >= window ? new DelinquencyModel.History(0, noCure, 0) : new DelinquencyModel.History(episodes, age, worst);
        }
        int aged = since < noCure ? Math.min(since + elapsed, noCure) : noCure;
        boolean rolled = aged >= window;
        return new DelinquencyModel.History(Math.max(rolled ? 0 : episodes, 1), rolled ? noCure : aged, Math.max(rolled ? 0 : worst, source));
    }

    public Prepared prepare(Request request) {
        return new Prepared(request, engine.prepare(request.base()));
    }

    /** Projects every request in one batched model call. */
    public List<Outcome> projectAll(List<Request> requests) {
        List<Prepared> prepared = new ArrayList<>(requests.size());
        int rows = 0;
        for (Request request : requests) {
            Prepared p = prepare(request);
            prepared.add(p);
            rows += p.rows();
        }
        float[] flat = new float[rows * model.width()];
        int[] firstRow = new int[prepared.size()];
        int at = 0;
        for (int i = 0; i < prepared.size(); i++) {
            firstRow[i] = at;
            prepared.get(i).writeRows(flat, at);
            at += prepared.get(i).rows();
        }
        double[] p = model.probabilities(flat, rows);
        DelinquencyModel.monotoneRepair(p, rows);
        List<Outcome> outcomes = new ArrayList<>(prepared.size());
        for (int i = 0; i < prepared.size(); i++) {
            outcomes.add(finish(prepared.get(i), p, firstRow[i]));
        }
        return outcomes;
    }

    public TermStructureEngine.TermStructure project(Request request, int displayMonths) {
        return view(projectAll(List.of(request)).get(0), displayMonths);
    }

    /**
     * Runs the recursion over probabilities laid out by {@link Prepared#writeRows} from model row {@code firstRow}
     * ({@code probabilities} holds five per model row), and assembles the loss and the staging.
     */
    Outcome finish(Prepared prepared, double[] probabilities, int firstRow) {
        TermStructureEngine.Projection projection = prepared.projection;
        TermStructureEngine.Plan main = projection.main;
        int first = firstRow / DelinquencyModel.STATES;      // the recursion counts the existing engine's rows, three model rows each
        StateRecursion.Result curve = StateRecursion.run(survival.transitionMatrix(),
                survival.regimeDistribution(projection.origin, projection.origin), prepared.request.startState(),
                DelinquencyModel.STATES, main.forced, main.rowOffset, probabilities, first);
        double note = projection.request.loan().originalInterestRate();
        Figures figures = figures(main, note, curve);

        Double referencePd = projection.request.originationPd12m();
        if (projection.reference != null) {
            TermStructureEngine.Plan ref = projection.reference;
            StateRecursion.Result reference = StateRecursion.run(survival.transitionMatrix(),
                    survival.regimeDistribution(projection.origin, projection.origination), DelinquencyModel.CURRENT,
                    DelinquencyModel.STATES, ref.forced, ref.rowOffset, probabilities, first + main.rows);
            referencePd = reference.cumulativeDefault();
        }
        int daysPastDue = projection.request.daysPastDue() == null ? 0 : projection.request.daysPastDue();
        int stage;
        String reason;
        if (daysPastDue >= 30) {
            stage = 2;
            reason = "30 or more days past due";
        } else if (referencePd != null && figures.pd12m() >= sicrRelative * referencePd && figures.pd12m() - referencePd >= sicrAbsolute) {
            stage = 2;
            reason = "Significant increase in credit risk since origination";
        } else {
            stage = 1;
            reason = projection.monthsOnBook == 0 ? "Newly originated" : "No significant increase in credit risk since origination";
        }
        double exposure = main.exposure[0];
        double impaired = main.lgd[0] * exposure;
        double ifrs9 = stage == 1 ? figures.ecl12m() : figures.eclLifetime();
        TermStructureEngine.Ecl ecl = new TermStructureEngine.Ecl(stage, reason, exposure, projection.fittedLgd, figures.lgdPeak(),
                figures.ecl12m(), figures.eclLifetime(), ifrs9, figures.eclLifetime(), referencePd);
        TermStructureEngine.Summary summary = new TermStructureEngine.Summary(figures.pd12m(), figures.pd24m(),
                curve.cumulativeDefault(), curve.cumulativePrepay(), figures.expectedLife(),
                curve.survival()[main.months - 1]);
        return new Outcome(prepared, curve, figures.discountedLoss(), summary, ecl, probabilities, first);
    }

    /** Loss, discounted at the note rate, and the lifetime and 12-month sums over a finished recursion. */
    Figures figures(TermStructureEngine.Plan main, double noteRate, StateRecursion.Result curve) {
        int months = main.months;
        double[] discountedLoss = new double[months];
        double monthlyDiscount = 1.0 / (1.0 + noteRate / 1200.0);
        double discount = 1.0, ecl12 = 0, eclLifetime = 0, expectedLife = 0, cumulative = 0, pd12 = 0, pd24 = 0, lgdPeak = 0;
        for (int i = 0; i < months; i++) {
            discount *= monthlyDiscount;
            discountedLoss[i] = curve.marginalDefault()[i] * main.lgd[i] * main.exposure[i] * discount;
            eclLifetime += discountedLoss[i];
            cumulative += curve.marginalDefault()[i];
            if (i < 12) {
                ecl12 += discountedLoss[i];
                pd12 = cumulative;
            }
            if (i < 24) {
                pd24 = cumulative;
            }
            expectedLife += i == 0 ? 1.0 : curve.survival()[i - 1];
            lgdPeak = Math.max(lgdPeak, main.lgd[i]);
        }
        return new Figures(pd12, pd24, curve.cumulativeDefault(), ecl12, eclLifetime, expectedLife, lgdPeak, discountedLoss);
    }

    /** The API view of an outcome, with the monthly series cut to {@code displayMonths}. */
    public TermStructureEngine.TermStructure view(Outcome o, int displayMonths) {
        TermStructureEngine.Projection projection = o.prepared.projection;
        TermStructureEngine.Plan p = projection.main;
        var loan = projection.request.loan();
        int shown = Math.max(1, Math.min(displayMonths, p.months));
        List<TermStructureEngine.MonthPoint> points = new ArrayList<>(shown);
        double discount = 1.0, monthlyDiscount = 1.0 / (1.0 + loan.originalInterestRate() / 1200.0);
        double cumulativeDefault = 0, cumulativePrepay = 0;
        StateRecursion.Result c = o.curve;
        for (int i = 0; i < shown; i++) {
            discount *= monthlyDiscount;
            cumulativeDefault += c.marginalDefault()[i];
            cumulativePrepay += c.marginalPrepay()[i];
            points.add(new TermStructureEngine.MonthPoint(projection.origin.plusMonths(p.firstK + i).toString(), p.age[i],
                    c.survival()[i], c.marginalDefault()[i], c.marginalPrepay()[i], cumulativeDefault, cumulativePrepay,
                    c.defaultHazard()[i], c.prepayHazard()[i], c.stressed()[i], p.exposure[i], p.lgd[i],
                    o.discountedLoss[i] / discount, o.discountedLoss[i], p.unemployment[i], 100.0 * p.hpiFactor[i], p.mtmLtv[i]));
        }
        YearMonth rateAsOf = market.rateAsOf();
        TermStructureEngine.Assumptions assumptions = new TermStructureEngine.Assumptions(projection.origin.toString(),
                rateAsOf.toString(), survival.regimeAsOf().toString(), projection.origination.toString(), projection.rateSpread,
                projection.marketRate, projection.startUnemployment, 1.0, 1.0, liquidationHaircut, loan.originalInterestRate());
        return new TermStructureEngine.TermStructure(projection.scenario.name(), model.version(), projection.monthsOnBook, p.months,
                o.summary, o.ecl, points, extrapolations(o), assumptions);
    }

    /** Where the scenario path leaves the range this model was trained on. */
    public List<TermStructureEngine.Extrapolation> extrapolations(Outcome outcome) {
        TermStructureEngine.Projection projection = outcome.prepared.projection;
        TermStructureEngine.Plan p = projection.main;
        List<TermStructureEngine.Extrapolation> found = new ArrayList<>();
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

    private void outside(List<TermStructureEngine.Extrapolation> found, TermStructureEngine.Projection projection, String driver,
                         double[] path) {
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
            found.add(new TermStructureEngine.Extrapolation(driver, "below", range.min(), lowest, below,
                    projection.origin.plusMonths(projection.main.firstK + firstBelow).toString()));
        }
        if (above > 0) {
            found.add(new TermStructureEngine.Extrapolation(driver, "above", range.max(), highest, above,
                    projection.origin.plusMonths(projection.main.firstK + firstAbove).toString()));
        }
    }
}
