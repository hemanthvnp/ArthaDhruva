package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.cvar.CvarResult;
import com.arthadhruva.riskengine.cvar.LoanRiskProfile;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Lifetime risk of a whole portfolio under several scenarios: every loan's term structure, added up into
 * expected credit loss, staging, a month-by-month run-off (balance at risk, defaults, prepayments, loss
 * emerging) and concentrations by state and by loan.
 *
 * <p><b>Cost.</b> A loan-scenario needs up to 2 x 480 model rows, so the work is cut into chunks of a few
 * loans, each chunk evaluated in one batched model call on the shared compute pool, a window of chunks at
 * a time so that concurrent runs take turns ({@link ChunkWindow}). Chunks are merged in the order they
 * were cut, not the order they finish, so a rerun reproduces every figure bit for bit whatever the thread
 * count.
 *
 * <p><b>Resuming.</b> A run may be given {@link Checkpoints}: each finished chunk is handed to them, and a
 * chunk they already hold is merged as stored instead of being projected again. A stored chunk is the
 * chunk's exact sums (doubles by their bits), keyed by a hash of everything those sums depend on, so a
 * resumed run produces the figures an uninterrupted one would have.
 *
 * <p><b>Large portfolios.</b> Above {@code risk.portfolio.max-loans} a simple random sample of that size
 * is projected and every total scaled by N/n; the result then carries the relative standard error of the
 * lifetime ECL, so the reader sees how much the sample costs in precision.
 *
 * <p><b>Expected and unexpected loss.</b> The baseline scenario also keeps its loan-by-loan results (the
 * listing behind the totals) and feeds them to the {@link CvarEngine}: the 12-month ECL is what the
 * portfolio is expected to lose in a year, the simulated distribution says how much worse a bad year
 * can be.
 */
@Service
public class PortfolioRiskEngine {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskEngine.class);

    /** Months of the run-off series kept per scenario. */
    public static final int SERIES_MONTHS = 120;
    /** The quantile of the loss distribution stored with a run: a one-in-a-thousand year, as in Basel's IRB formula. */
    public static final double CAPITAL_CONFIDENCE = 0.999;
    public static final int CAPITAL_SCENARIOS = 20_000;

    private static final int LOANS_PER_CHUNK = 8;
    private static final int TOP_LOANS = 10;
    private static final int TOP_STATES = 12;

    private final TermStructureEngine engine;
    private final SurvivalModel model;
    private final LoanInputValidator validator;
    private final CvarEngine lossEngine;
    private final ExecutorService workers;
    private final int maxLoans;

    public PortfolioRiskEngine(TermStructureEngine engine, SurvivalModel model, LoanInputValidator validator,
                               CvarEngine lossEngine, @Qualifier("riskComputeExecutor") ExecutorService workers,
                               @Value("${risk.portfolio.max-loans:5000}") int maxLoans) {
        this.engine = engine;
        this.model = model;
        this.validator = validator;
        this.lossEngine = lossEngine;
        this.workers = workers;
        this.maxLoans = maxLoans;
    }

    /** One IFRS 9 stage's share of the portfolio. */
    public record StageBreakdown(int stage, int loans, double exposure, double ecl) {
    }

    public record StateRisk(String state, int loans, double exposure, double eclLifetime, double eclIfrs9) {
    }

    public record LoanRisk(String loanId, String state, double exposure, double pd12m, double pdLifetime,
                           double eclLifetime, int stage) {
    }

    /**
     * One loan's result under the baseline scenario.
     *
     * @param lgd    loss given default at the start of the projection
     * @param weight how many portfolio loans this one stands for: 1 unless the portfolio was sampled
     */
    public record LoanResult(String loanId, String state, double exposure, double pd12m, double pdLifetime, double lgd,
                             double ecl12m, double eclLifetime, double eclIfrs9, int stage, String stageReason,
                             double weight) {
    }

    /**
     * Month-by-month run-off, in currency.
     *
     * @param balanceAtRisk expected balance still on the book at the start of the month
     * @param defaults      balance expected to default during the month
     * @param prepayments   balance expected to prepay during the month
     * @param expectedLoss  discounted credit loss expected to arise in the month
     */
    public record Series(List<String> months, double[] balanceAtRisk, double[] defaults, double[] prepayments,
                         double[] expectedLoss) {
    }

    /**
     * @param pd12m                    exposure-weighted 12-month PD
     * @param coverage                 IFRS 9 ECL as a share of exposure
     * @param extrapolatedLoans        loans whose path leaves the model's training range, per driver and direction
     * @param eclRelativeStandardError sampling error of {@code eclLifetime}; null when every loan was projected
     * @param lossDistribution         the one-year loss distribution around the 12-month ECL (see
     *                                 {@link #lossRequest}); on the baseline scenario only, and null if the
     *                                 simulation could not run
     */
    public record ScenarioRisk(String scenario, String description, int loans, double exposure, double pd12m,
                               double pdLifetime, double ecl12m, double eclLifetime, double eclIfrs9, double eclCecl,
                               double coverage, List<StageBreakdown> stages, Series series, List<StateRisk> states,
                               List<LoanRisk> topLoans, Map<String, Integer> extrapolatedLoans,
                               Double eclRelativeStandardError, CvarResult lossDistribution) {
    }

    /**
     * @param loans         loans in the portfolio
     * @param excluded      loans that could not be projected (outside the model's domain, or matured)
     * @param projected     loans actually projected: fewer than {@code loans - excluded} when sampled
     * @param baselineLoans every projected loan's baseline result, by loan id; empty when the baseline was
     *                      not among the scenarios run
     */
    public record PortfolioRisk(String modelVersion, String forecastOrigin, int loans, int excluded, int projected,
                                List<ScenarioRisk> scenarios, List<LoanResult> baselineLoans) {
    }

    /** The chunks a run has finished, kept somewhere that outlives the worker computing them. */
    public interface Checkpoints {

        /** Keeps nothing: every chunk is projected. */
        Checkpoints NONE = new Checkpoints() {
            @Override
            public Map<String, byte[]> load() {
                return Map.of();
            }

            @Override
            public void save(String key, byte[] chunk) {
            }
        };

        /** Every chunk stored so far, by key; called once, before any chunk is started. */
        Map<String, byte[]> load();

        /** Called from the thread that called {@link #run}, once per chunk projected. */
        void save(String key, byte[] chunk);
    }

    /** A chunk as it reaches the merge: {@code key} is set when it was projected just now and is to be stored. */
    private record Done(Chunk chunk, String key) {
    }

    public PortfolioRisk run(List<LoanFeatures> portfolio, List<Scenario> scenarios, long seed, IntConsumer progress) {
        return run(portfolio, scenarios, seed, progress, Checkpoints.NONE);
    }

    /**
     * @param seed        fixes which loans are sampled when the portfolio exceeds the limit, and the random
     *                    streams of the loss simulation
     * @param progress    called with the number of loans finished so far, from the calling thread
     * @param checkpoints where finished chunks are kept and found again
     */
    public PortfolioRisk run(List<LoanFeatures> portfolio, List<Scenario> scenarios, long seed, IntConsumer progress,
                             Checkpoints checkpoints) {
        // A fixed order first: the sample, and the order figures are summed in, must not depend on how
        // the caller happened to list the loans.
        List<LoanFeatures> loans = new ArrayList<>(portfolio);
        loans.sort(Comparator.comparing(LoanFeatures::loanId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (loans.size() > maxLoans) {
            Collections.shuffle(loans, new Random(seed));
            loans = loans.subList(0, maxLoans);
        }
        // Scale a sample up to the portfolio; excluded loans are assumed spread like the rest.
        double scale = loans.isEmpty() ? 1.0 : (double) portfolio.size() / loans.size();
        int baseline = baselineIndex(scenarios);

        boolean keep = checkpoints != Checkpoints.NONE;
        Map<String, byte[]> stored = keep ? checkpoints.load() : Map.of();
        String runKey = keep ? runKey(scenarios, baseline, scale) : null;
        List<Supplier<Future<Done>>> starts = new ArrayList<>();
        for (int from = 0; from < loans.size(); from += LOANS_PER_CHUNK) {
            List<LoanFeatures> slice = loans.subList(from, Math.min(loans.size(), from + LOANS_PER_CHUNK));
            String key = keep ? sha256(runKey + slice) : null;
            Chunk found = key == null ? null : Chunk.read(stored.get(key), scenarios.size());
            starts.add(found != null ? () -> CompletableFuture.completedFuture(new Done(found, null))
                    : () -> workers.submit(() -> new Done(evaluate(slice, scenarios, baseline, scale), key)));
        }
        Chunk total = new Chunk(scenarios.size());
        try {
            ChunkWindow.run(starts, done -> {
                total.merge(done.chunk());
                if (done.key() != null) {
                    checkpoints.save(done.key(), done.chunk().write());
                }
                progress.accept(total.projected + total.excluded);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Portfolio run interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Portfolio run failed", e.getCause());
        }

        boolean sampled = loans.size() < portfolio.size();
        int excluded = (int) Math.round(total.excluded * scale);
        List<String> months = new ArrayList<>(SERIES_MONTHS);
        for (int k = 1; k <= SERIES_MONTHS; k++) {
            months.add(engine.forecastOrigin().plusMonths(k).toString());
        }
        List<LoanResult> baselineLoans = new ArrayList<>(total.baselineLoans);
        baselineLoans.sort(Comparator.comparing(LoanResult::loanId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<ScenarioRisk> results = new ArrayList<>(scenarios.size());
        for (int s = 0; s < scenarios.size(); s++) {
            results.add(total.byScenario[s].result(scenarios.get(s), scale, months, sampled, portfolio.size() - excluded,
                    s == baseline ? lossDistribution(baselineLoans, seed) : null));
        }
        return new PortfolioRisk(model.version(), engine.forecastOrigin().toString(), portfolio.size(), excluded,
                total.projected, results, List.copyOf(baselineLoans));
    }

    /**
     * What every chunk of one run has in common, of the things its figures depend on: the model, the month
     * the projection starts from, the scenarios and their order, and the scale a sample is weighted by.
     */
    private String runKey(List<Scenario> scenarios, int baseline, double scale) {
        return CHECKPOINT_FORMAT + "|" + modelKey() + "|" + scenarios + "|" + baseline + "|" + Double.doubleToLongBits(scale) + "|";
    }

    /**
     * The model a projection made now comes from, and the month it starts from: what any checkpoint of
     * projected figures has to be keyed by, whichever engine wrote it.
     */
    public String modelKey() {
        return model.version() + "|" + model.artifactSha256() + "|" + engine.forecastOrigin();
    }

    /** Hex SHA-256 of {@code text}: the key of a checkpoint, from everything its content depends on. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int baselineIndex(List<Scenario> scenarios) {
        for (int s = 0; s < scenarios.size(); s++) {
            if (Scenario.BASELINE.name().equals(scenarios.get(s).name())) {
                return s;
            }
        }
        return -1;
    }

    /**
     * The request for the one-year loss simulation of a set of loan results. Each loan defaults with its
     * 12-month PD; what it then loses is its 12-month ECL divided by that probability. Taking the
     * severity from the ECL, rather than from LGD x balance, carries the amortization, the discounting
     * and the month-by-month LGD of the projection into the simulation, and makes the simulated mean
     * exactly the 12-month ECL: expected and unexpected loss are then statements about the same loss.
     *
     * <p>A sampled loan is simulated at {@code weight} times its size. That keeps expected loss and
     * systematic risk right and slightly overstates the name concentration of a portfolio that large,
     * which is small to begin with.
     *
     * @return null when no loan carries any default risk
     */
    public static CvarRequest lossRequest(List<LoanResult> loans, double confidenceLevel, int numScenarios,
                                          double assetCorrelation, Long seed, boolean importanceSampling) {
        List<LoanRiskProfile> profiles = new ArrayList<>(loans.size());
        for (LoanResult loan : loans) {
            if (loan.pd12m() <= 0 || loan.exposure() <= 0 || loan.ecl12m() <= 0) {
                continue;
            }
            double severity = Math.min(1.0, loan.ecl12m() / (loan.pd12m() * loan.exposure()));
            profiles.add(new LoanRiskProfile(loan.loanId(), Math.min(1.0, loan.pd12m()), severity, loan.exposure() * loan.weight()));
        }
        return profiles.isEmpty() ? null
                : new CvarRequest(profiles, confidenceLevel, numScenarios, assetCorrelation, seed, importanceSampling, true);
    }

    private CvarResult lossDistribution(List<LoanResult> loans, long seed) {
        CvarRequest request = lossRequest(loans, CAPITAL_CONFIDENCE, CAPITAL_SCENARIOS, CvarRequest.DEFAULT_ASSET_CORRELATION,
                seed, true);
        if (request == null) {
            return null;
        }
        try {
            return lossEngine.simulate(request);
        } catch (CvarEngine.BusyException | CvarEngine.TooLargeException e) {
            // The projection is the result of the run; the loss distribution can be simulated on demand.
            log.warn("Portfolio loss distribution skipped: {}", e.getMessage());
            return null;
        }
    }

    private Chunk evaluate(List<LoanFeatures> loans, List<Scenario> scenarios, int baseline, double weight) {
        Chunk chunk = new Chunk(scenarios.size());
        List<TermStructureEngine.Projection> projections = new ArrayList<>(loans.size() * scenarios.size());
        int rows = 0;
        for (LoanFeatures raw : loans) {
            int before = projections.size();
            try {
                LoanFeatures loan = raw.normalized();
                validator.check(loan);
                for (Scenario scenario : scenarios) {
                    projections.add(engine.prepare(new TermStructureEngine.Request(loan, scenario, null, null, null, null)));
                }
                for (int i = before; i < projections.size(); i++) {
                    rows += projections.get(i).rows();
                }
                chunk.projected++;
            } catch (LoanInputValidator.InvalidLoanException | TermStructureEngine.InvalidProjectionException e) {
                projections.subList(before, projections.size()).clear();
                chunk.excluded++;
            }
        }
        float[] flat = new float[rows * engine.features()];
        int[] firstRow = new int[projections.size()];
        int at = 0;
        for (int i = 0; i < projections.size(); i++) {
            firstRow[i] = at;
            projections.get(i).writeRows(flat, at);
            at += projections.get(i).rows();
        }
        double[] hazards = engine.hazards(flat, rows);
        for (int i = 0; i < projections.size(); i++) {
            TermStructureEngine.Outcome outcome = engine.finish(projections.get(i), hazards, firstRow[i]);
            int scenario = i % scenarios.size();
            chunk.byScenario[scenario].add(outcome, engine.extrapolations(outcome));
            if (scenario == baseline) {
                LoanFeatures loan = outcome.projection.request.loan();
                TermStructureEngine.Ecl ecl = outcome.ecl();
                chunk.baselineLoans.add(new LoanResult(loan.loanId(), loan.propertyState(), ecl.exposure(),
                        outcome.summary().pd12m(), outcome.summary().pdLifetime(), ecl.lgd(), ecl.ecl12m(), ecl.eclLifetime(),
                        ecl.eclIfrs9(), ecl.stage(), ecl.stageReason(), weight));
            }
        }
        return chunk;
    }

    /** What one chunk (or the merge of many) adds up to. */
    private static final class Chunk {
        final Totals[] byScenario;
        final List<LoanResult> baselineLoans = new ArrayList<>();
        int projected;
        int excluded;

        Chunk(int scenarios) {
            byScenario = new Totals[scenarios];
            for (int s = 0; s < scenarios; s++) {
                byScenario[s] = new Totals();
            }
        }

        void merge(Chunk other) {
            projected += other.projected;
            excluded += other.excluded;
            baselineLoans.addAll(other.baselineLoans);
            for (int s = 0; s < byScenario.length; s++) {
                byScenario[s].merge(other.byScenario[s]);
            }
        }

        /** The chunk exactly as it is: every double by its bits, every collection in the order it is held. */
        byte[] write() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(CHECKPOINT_FORMAT);
                out.writeInt(byScenario.length);
                out.writeInt(projected);
                out.writeInt(excluded);
                out.writeInt(baselineLoans.size());
                for (LoanResult loan : baselineLoans) {
                    writeText(out, loan.loanId());
                    writeText(out, loan.state());
                    writeAll(out, new double[]{loan.exposure(), loan.pd12m(), loan.pdLifetime(), loan.lgd(), loan.ecl12m(),
                            loan.eclLifetime(), loan.eclIfrs9()});
                    out.writeInt(loan.stage());
                    writeText(out, loan.stageReason());
                    out.writeDouble(loan.weight());
                }
                for (Totals totals : byScenario) {
                    totals.write(out);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);   // an in-memory stream does not fail
            }
            return bytes.toByteArray();
        }

        /** The chunk {@link #write} stored; null when there is none or it cannot be read, so it is projected again. */
        static Chunk read(byte[] stored, int scenarios) {
            if (stored == null) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stored))) {
                if (in.readByte() != CHECKPOINT_FORMAT || in.readInt() != scenarios) {
                    return null;
                }
                Chunk chunk = new Chunk(scenarios);
                chunk.projected = in.readInt();
                chunk.excluded = in.readInt();
                int loans = in.readInt();
                for (int i = 0; i < loans; i++) {
                    chunk.baselineLoans.add(new LoanResult(readText(in), readText(in), in.readDouble(), in.readDouble(),
                            in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readInt(),
                            readText(in), in.readDouble()));
                }
                for (Totals totals : chunk.byScenario) {
                    totals.read(in);
                }
                return in.available() == 0 ? chunk : null;
            } catch (IOException | RuntimeException e) {
                log.warn("A stored chunk of a portfolio run could not be read; projecting it again: {}", e.toString());
                return null;
            }
        }
    }

    /** Changes whenever a chunk's stored form or what it depends on does, so an older checkpoint is not read. */
    private static final int CHECKPOINT_FORMAT = 1;

    private static void writeText(DataOutputStream out, String text) throws IOException {
        out.writeBoolean(text != null);
        if (text != null) {
            out.writeUTF(text);
        }
    }

    private static String readText(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void writeAll(DataOutputStream out, double[] values) throws IOException {
        for (double value : values) {
            out.writeDouble(value);
        }
    }

    private static void readAll(DataInputStream in, double[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readDouble();
        }
    }

    private static final class Totals {
        int loans;
        double exposure, exposurePd12, exposurePdLifetime, ecl12, eclLifetime, eclLifetimeSquares, eclIfrs9, eclCecl;
        final int[] stageLoans = new int[3];
        final double[] stageExposure = new double[3];
        final double[] stageEcl = new double[3];
        final double[] balanceAtRisk = new double[SERIES_MONTHS];
        final double[] defaults = new double[SERIES_MONTHS];
        final double[] prepayments = new double[SERIES_MONTHS];
        final double[] expectedLoss = new double[SERIES_MONTHS];
        final Map<String, double[]> states = new HashMap<>();          // loans, exposure, eclLifetime, eclIfrs9
        final Map<String, Integer> extrapolated = new TreeMap<>();
        final PriorityQueue<LoanRisk> top = new PriorityQueue<>(Comparator.comparingDouble(LoanRisk::eclLifetime));

        void add(TermStructureEngine.Outcome o, List<TermStructureEngine.Extrapolation> extrapolations) {
            TermStructureEngine.Summary summary = o.summary();
            TermStructureEngine.Ecl ecl = o.ecl();
            LoanFeatures loan = o.projection.request.loan();
            loans++;
            exposure += ecl.exposure();
            exposurePd12 += ecl.exposure() * summary.pd12m();
            exposurePdLifetime += ecl.exposure() * summary.pdLifetime();
            ecl12 += ecl.ecl12m();
            eclLifetime += ecl.eclLifetime();
            eclLifetimeSquares += ecl.eclLifetime() * ecl.eclLifetime();
            eclIfrs9 += ecl.eclIfrs9();
            eclCecl += ecl.eclCecl();
            stageLoans[ecl.stage() - 1]++;
            stageExposure[ecl.stage() - 1] += ecl.exposure();
            stageEcl[ecl.stage() - 1] += ecl.eclIfrs9();

            double[] balance = o.projection.main.exposure;
            int months = Math.min(SERIES_MONTHS, o.months());
            for (int k = 0; k < months; k++) {
                balanceAtRisk[k] += (k == 0 ? 1.0 : o.survival[k - 1]) * balance[k];
                defaults[k] += o.marginalDefault[k] * balance[k];
                prepayments[k] += o.marginalPrepay[k] * balance[k];
                expectedLoss[k] += o.discountedLoss[k];
            }
            double[] state = states.computeIfAbsent(loan.propertyState(), s -> new double[4]);
            state[0]++;
            state[1] += ecl.exposure();
            state[2] += ecl.eclLifetime();
            state[3] += ecl.eclIfrs9();
            for (TermStructureEngine.Extrapolation e : extrapolations) {
                extrapolated.merge(e.driver() + ":" + e.direction(), 1, Integer::sum);
            }
            offer(new LoanRisk(loan.loanId(), loan.propertyState(), ecl.exposure(), summary.pd12m(), summary.pdLifetime(),
                    ecl.eclLifetime(), ecl.stage()));
        }

        private void offer(LoanRisk loan) {
            top.add(loan);
            if (top.size() > TOP_LOANS) {
                top.poll();
            }
        }

        void write(DataOutputStream out) throws IOException {
            out.writeInt(loans);
            writeAll(out, new double[]{exposure, exposurePd12, exposurePdLifetime, ecl12, eclLifetime, eclLifetimeSquares, eclIfrs9, eclCecl});
            for (int count : stageLoans) {
                out.writeInt(count);
            }
            writeAll(out, stageExposure);
            writeAll(out, stageEcl);
            writeAll(out, balanceAtRisk);
            writeAll(out, defaults);
            writeAll(out, prepayments);
            writeAll(out, expectedLoss);
            out.writeInt(states.size());
            for (Map.Entry<String, double[]> state : states.entrySet()) {
                writeText(out, state.getKey());
                writeAll(out, state.getValue());
            }
            out.writeInt(extrapolated.size());
            for (Map.Entry<String, Integer> driver : extrapolated.entrySet()) {
                out.writeUTF(driver.getKey());
                out.writeInt(driver.getValue());
            }
            // In the queue's own order: added back in that order, the queue is the same heap, so a later
            // merge breaks ties between equal losses exactly as it would have.
            out.writeInt(top.size());
            for (LoanRisk loan : top) {
                writeText(out, loan.loanId());
                writeText(out, loan.state());
                writeAll(out, new double[]{loan.exposure(), loan.pd12m(), loan.pdLifetime(), loan.eclLifetime()});
                out.writeInt(loan.stage());
            }
        }

        void read(DataInputStream in) throws IOException {
            loans = in.readInt();
            exposure = in.readDouble();
            exposurePd12 = in.readDouble();
            exposurePdLifetime = in.readDouble();
            ecl12 = in.readDouble();
            eclLifetime = in.readDouble();
            eclLifetimeSquares = in.readDouble();
            eclIfrs9 = in.readDouble();
            eclCecl = in.readDouble();
            for (int i = 0; i < stageLoans.length; i++) {
                stageLoans[i] = in.readInt();
            }
            readAll(in, stageExposure);
            readAll(in, stageEcl);
            readAll(in, balanceAtRisk);
            readAll(in, defaults);
            readAll(in, prepayments);
            readAll(in, expectedLoss);
            for (int i = in.readInt(); i > 0; i--) {
                String state = readText(in);
                double[] values = new double[4];
                readAll(in, values);
                states.put(state, values);
            }
            for (int i = in.readInt(); i > 0; i--) {
                extrapolated.put(in.readUTF(), in.readInt());
            }
            for (int i = in.readInt(); i > 0; i--) {
                top.add(new LoanRisk(readText(in), readText(in), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readInt()));
            }
        }

        void merge(Totals other) {
            loans += other.loans;
            exposure += other.exposure;
            exposurePd12 += other.exposurePd12;
            exposurePdLifetime += other.exposurePdLifetime;
            ecl12 += other.ecl12;
            eclLifetime += other.eclLifetime;
            eclLifetimeSquares += other.eclLifetimeSquares;
            eclIfrs9 += other.eclIfrs9;
            eclCecl += other.eclCecl;
            for (int i = 0; i < 3; i++) {
                stageLoans[i] += other.stageLoans[i];
                stageExposure[i] += other.stageExposure[i];
                stageEcl[i] += other.stageEcl[i];
            }
            for (int k = 0; k < SERIES_MONTHS; k++) {
                balanceAtRisk[k] += other.balanceAtRisk[k];
                defaults[k] += other.defaults[k];
                prepayments[k] += other.prepayments[k];
                expectedLoss[k] += other.expectedLoss[k];
            }
            other.states.forEach((state, v) -> {
                double[] mine = states.computeIfAbsent(state, s -> new double[4]);
                for (int i = 0; i < 4; i++) {
                    mine[i] += v[i];
                }
            });
            other.extrapolated.forEach((key, count) -> extrapolated.merge(key, count, Integer::sum));
            other.top.forEach(this::offer);
        }

        ScenarioRisk result(Scenario scenario, double scale, List<String> months, boolean sampled, int population,
                            CvarResult lossDistribution) {
            List<StageBreakdown> stages = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                stages.add(new StageBreakdown(i + 1, scaled(stageLoans[i], scale), stageExposure[i] * scale, stageEcl[i] * scale));
            }
            List<StateRisk> byState = states.entrySet().stream()
                    .map(e -> new StateRisk(e.getKey(), scaled((int) e.getValue()[0], scale), e.getValue()[1] * scale,
                            e.getValue()[2] * scale, e.getValue()[3] * scale))
                    .sorted(Comparator.comparingDouble(StateRisk::eclLifetime).reversed().thenComparing(StateRisk::state))
                    .limit(TOP_STATES).toList();
            List<LoanRisk> topLoans = top.stream()
                    .sorted(Comparator.comparingDouble(LoanRisk::eclLifetime).reversed().thenComparing(LoanRisk::loanId,
                            Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            Map<String, Integer> extrapolatedLoans = new TreeMap<>();
            extrapolated.forEach((key, count) -> extrapolatedLoans.put(key, scaled(count, scale)));

            Double relativeError = null;
            if (sampled && loans > 1 && eclLifetime > 0) {
                double mean = eclLifetime / loans;
                double variance = Math.max(0, (eclLifetimeSquares - loans * mean * mean) / (loans - 1));
                double finitePopulation = Math.max(0, 1.0 - (double) loans / population);
                relativeError = Math.sqrt(finitePopulation * variance / loans) / mean;
            }
            return new ScenarioRisk(scenario.name(), scenario.description(), scaled(loans, scale), exposure * scale,
                    exposure > 0 ? exposurePd12 / exposure : 0, exposure > 0 ? exposurePdLifetime / exposure : 0,
                    ecl12 * scale, eclLifetime * scale, eclIfrs9 * scale, eclCecl * scale,
                    exposure > 0 ? eclIfrs9 / exposure : 0, stages,
                    new Series(months, times(balanceAtRisk, scale), times(defaults, scale), times(prepayments, scale),
                            times(expectedLoss, scale)),
                    byState, topLoans, extrapolatedLoans, relativeError, lossDistribution);
        }

        private static int scaled(int count, double scale) {
            return (int) Math.round(count * scale);
        }

        private static double[] times(double[] values, double scale) {
            double[] out = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                out[i] = values[i] * scale;
            }
            return out;
        }
    }
}
