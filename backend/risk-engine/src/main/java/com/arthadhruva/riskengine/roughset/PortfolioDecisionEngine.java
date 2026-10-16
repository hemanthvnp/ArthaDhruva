package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import com.arthadhruva.riskengine.survival.ChunkWindow;
import com.arthadhruva.riskengine.survival.PortfolioRiskEngine;
import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The three-way decision for every loan of a portfolio under one target scenario, and what it adds up to.
 *
 * <p>Like {@link DecisionService} it is an overlay: it projects through {@link TermStructureEngine} and
 * leaves the portfolio engine alone. Loans are judged in fixed-size chunks on the compute pool and
 * the chunks are merged in loan-id order, so every figure, including the floating-point sums, is the same on
 * any number of threads. Each loan's variants come from a counter-based seed derived from that loan (or from
 * the caller's seed), never from a stream shared between loans.
 *
 * <p><b>Resuming.</b> As in the portfolio engine, a run may be given checkpoints: a judged chunk is handed
 * to them, and one they already hold is taken as stored instead of being judged again, with the same
 * result to the bit.
 *
 * <p><b>Cost.</b> A decision projects {@code members} variants per loan, so a run costs
 * {@code loans x members} projections (about 114 ms each on one worker, see PERFORMANCE.md). A run
 * is refused, not sampled, above {@code risk.portfolio.decision.max-projections}: the engine's sampling
 * scales totals up, which has no meaning for a per-loan classification.
 */
@Service
public class PortfolioDecisionEngine {

    static final int LOANS_PER_CHUNK = 4;

    private final DecisionService decisions;
    private final LoanInputValidator validator;
    private final ExecutorService workers;
    private final int maxProjections;

    public PortfolioDecisionEngine(DecisionService decisions, LoanInputValidator validator,
                                   @Qualifier("riskComputeExecutor") ExecutorService workers,
                                   @Value("${risk.portfolio.decision.max-projections:2000}") int maxProjections) {
        this.decisions = decisions;
        this.validator = validator;
        this.workers = workers;
        this.maxProjections = maxProjections;
    }

    /** The run would project more than the configured limit. */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * One loan's result.
     *
     * @param exposure   exposure under the unperturbed target
     * @param eclBase    lifetime expected credit loss under the unperturbed target
     * @param eclMin     lowest lifetime expected credit loss across the variants
     * @param eclMax     highest lifetime expected credit loss across the variants
     */
    public record LoanRegion(String loanId, String state, double exposure, Region region, int high, int members, double metricMin,
                             double metricMedian, double metricMax, double baseMetric, double eclBase, double eclMin, double eclMax,
                             boolean extrapolated) {
    }

    /** One region's share of the book. */
    public record RegionTotal(Region region, int loans, double exposure, double loanShare, double exposureShare) {
    }

    /** Boundary loans of one property state. */
    public record StateBoundary(String state, int loans, double exposure) {
    }

    /**
     * What a run was asked and how the variants were drawn, echoed with every result.
     *
     * @param seed null when each loan's seed was derived from the loan itself
     */
    public record Parameters(RoughSetMetric metric, double threshold, double tolerance, GranuleKind granuleKind, int members,
                             double shockBand, int timingBandMonths, List<String> scenarios, Long seed) {
    }

    /**
     * @param eclLower     lifetime expected loss of the loans that are certainly high, each at its lowest across the
     *                     variants: a loss the high-risk part of the book carries under every variant
     * @param eclUpper     lifetime expected loss of the loans that are certainly high or on the boundary, each at its
     *                     highest across the variants: the most the high-risk part could carry
     * @param eclHighBase  lifetime expected loss of the loans that are high under the unperturbed target; with
     *                     tolerance 0 it lies between {@code eclLower} and {@code eclUpper}
     * @param loans        every judged loan, in loan-id order
     */
    public record Result(String scenario, Parameters parameters, int loans, int excluded, double exposure,
                         List<RegionTotal> regions, double eclLower, double eclUpper, double eclHighBase,
                         List<StateBoundary> boundaryByState, List<LoanRegion> loanRegions, String method) {
    }

    public int maxProjections() {
        return maxProjections;
    }

    /** @throws TooLargeException when {@code loans x members} exceeds the limit */
    public void checkSize(int loans, int members) {
        long projections = (long) loans * members;
        if (projections > maxProjections) {
            throw new TooLargeException("Region analysis projects " + loans + " loans x " + members + " variants = " + projections
                    + " projections, above the limit of " + maxProjections + ". Use fewer variants or a smaller portfolio.");
        }
    }

    /**
     * @param progress called with the number of loans judged so far, from the calling thread
     */
    public Result run(List<LoanFeatures> portfolio, Scenario target, DecisionOptions.Resolved options, IntConsumer progress) {
        return run(portfolio, target, options, progress, PortfolioRiskEngine.Checkpoints.NONE, null);
    }

    /**
     * @param progress    called with the number of loans judged so far, from the calling thread
     * @param checkpoints where judged chunks are kept and found again
     * @param modelKey    the model the judgements come from ({@link PortfolioRiskEngine#modelKey}); part of
     *                    every checkpoint's key, so one written under another model is never read
     */
    public Result run(List<LoanFeatures> portfolio, Scenario target, DecisionOptions.Resolved options, IntConsumer progress,
                      PortfolioRiskEngine.Checkpoints checkpoints, String modelKey) {
        List<LoanFeatures> loans = new ArrayList<>(portfolio);
        loans.sort(Comparator.comparing(LoanFeatures::loanId, Comparator.nullsLast(Comparator.naturalOrder())));

        // A window of chunks at a time, so a region analysis and another run take turns on the compute pool.
        boolean keep = checkpoints != PortfolioRiskEngine.Checkpoints.NONE;
        Map<String, byte[]> stored = keep ? checkpoints.load() : Map.of();
        String runKey = keep ? "regions|" + CHECKPOINT_FORMAT + "|" + modelKey + "|" + target + "|" + options + "|" : null;
        List<Supplier<Future<Chunk>>> starts = new ArrayList<>();
        for (int from = 0; from < loans.size(); from += LOANS_PER_CHUNK) {
            List<LoanFeatures> slice = loans.subList(from, Math.min(loans.size(), from + LOANS_PER_CHUNK));
            String key = keep ? PortfolioRiskEngine.sha256(runKey + slice) : null;
            Chunk found = key == null ? null : Chunk.read(stored.get(key));
            starts.add(found != null ? () -> CompletableFuture.completedFuture(found) : () -> workers.submit(() -> {
                Chunk chunk = judge(slice, target, options);
                chunk.key = key;
                return chunk;
            }));
        }
        List<LoanRegion> judged = new ArrayList<>(loans.size());
        int[] excluded = new int[1];
        try {
            ChunkWindow.run(starts, chunk -> {
                judged.addAll(chunk.loans);
                excluded[0] += chunk.excluded;
                if (chunk.key != null) {
                    checkpoints.save(chunk.key, chunk.write());
                }
                progress.accept(judged.size() + excluded[0]);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Region analysis interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Region analysis failed", e.getCause());
        }
        return aggregate(target, options, judged, excluded[0], portfolio.size());
    }

    /** Changes whenever a judged chunk's stored form does, so an older checkpoint is not read. */
    private static final int CHECKPOINT_FORMAT = 1;
    private static final Region[] REGIONS = Region.values();

    private static final class Chunk {
        final List<LoanRegion> loans = new ArrayList<>();
        int excluded;
        /** Set when the chunk was judged just now and is to be stored. */
        String key;

        /** The chunk exactly as it is, every double by its bits. */
        byte[] write() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(CHECKPOINT_FORMAT);
                out.writeInt(excluded);
                out.writeInt(loans.size());
                for (LoanRegion l : loans) {
                    writeText(out, l.loanId());
                    writeText(out, l.state());
                    out.writeDouble(l.exposure());
                    out.writeByte(l.region().ordinal());
                    out.writeInt(l.high());
                    out.writeInt(l.members());
                    for (double v : new double[]{l.metricMin(), l.metricMedian(), l.metricMax(), l.baseMetric(), l.eclBase(),
                            l.eclMin(), l.eclMax()}) {
                        out.writeDouble(v);
                    }
                    out.writeBoolean(l.extrapolated());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);   // an in-memory stream does not fail
            }
            return bytes.toByteArray();
        }

        /** The chunk {@link #write} stored; null when there is none or it cannot be read, so it is judged again. */
        static Chunk read(byte[] stored) {
            if (stored == null) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stored))) {
                if (in.readByte() != CHECKPOINT_FORMAT) {
                    return null;
                }
                Chunk chunk = new Chunk();
                chunk.excluded = in.readInt();
                for (int i = in.readInt(); i > 0; i--) {
                    chunk.loans.add(new LoanRegion(readText(in), readText(in), in.readDouble(), REGIONS[in.readByte()], in.readInt(),
                            in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                            in.readDouble(), in.readDouble(), in.readBoolean()));
                }
                return in.available() == 0 ? chunk : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        out.writeBoolean(text != null);
        if (text != null) {
            out.writeUTF(text);
        }
    }

    private static String readText(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private Chunk judge(List<LoanFeatures> slice, Scenario target, DecisionOptions.Resolved options) {
        Chunk chunk = new Chunk();
        for (LoanFeatures raw : slice) {
            try {
                LoanFeatures loan = raw.normalized();
                validator.check(loan);
                DecisionService.Decision d = decisions.decide(new TermStructureEngine.Request(loan, target, null, null, null, null), options);
                chunk.loans.add(new LoanRegion(raw.loanId(), loan.propertyState(), d.exposure(), d.region(), d.high(), d.members(), d.min(),
                        d.median(), d.max(), d.baseMetric(), d.eclBase(), d.eclMin(), d.eclMax(), d.extrapolated()));
            } catch (LoanInputValidator.InvalidLoanException | TermStructureEngine.InvalidProjectionException e) {
                chunk.excluded++;
            }
        }
        return chunk;
    }

    /** Sums in the order given (loan-id order), so the totals do not depend on how the work was split. */
    static Result aggregate(Scenario target, DecisionOptions.Resolved options, List<LoanRegion> loans, int excluded, int portfolioSize) {
        Map<Region, int[]> counts = new EnumMap<>(Region.class);
        Map<Region, double[]> exposures = new EnumMap<>(Region.class);
        for (Region r : Region.values()) {
            counts.put(r, new int[1]);
            exposures.put(r, new double[1]);
        }
        Map<String, double[]> boundary = new TreeMap<>();
        double exposure = 0;
        double lower = 0;
        double upper = 0;
        double highBase = 0;
        for (LoanRegion l : loans) {
            counts.get(l.region())[0]++;
            exposures.get(l.region())[0] += l.exposure();
            exposure += l.exposure();
            if (l.region() == Region.CERTAIN_HIGH) {
                lower += l.eclMin();
            }
            if (l.region() != Region.CERTAIN_LOW) {
                upper += l.eclMax();
            }
            if (l.baseMetric() >= options.threshold()) {
                highBase += l.eclBase();
            }
            if (l.region() == Region.BOUNDARY) {
                double[] s = boundary.computeIfAbsent(l.state(), k -> new double[2]);
                s[0]++;
                s[1] += l.exposure();
            }
        }
        List<RegionTotal> totals = new ArrayList<>(3);
        for (Region r : Region.values()) {
            int n = counts.get(r)[0];
            double e = exposures.get(r)[0];
            totals.add(new RegionTotal(r, n, e, loans.isEmpty() ? 0.0 : (double) n / loans.size(), exposure > 0 ? e / exposure : 0.0));
        }
        List<StateBoundary> byState = new ArrayList<>(boundary.size());
        boundary.forEach((state, s) -> byState.add(new StateBoundary(state, (int) s[0], s[1])));
        byState.sort(Comparator.comparingDouble(StateBoundary::exposure).reversed().thenComparing(StateBoundary::state));
        Parameters parameters = new Parameters(options.metric(), options.threshold(), options.tolerance(), options.kind(), options.members(),
                options.shockBand(), options.timingBandMonths(), options.scenarios(), options.seed());
        return new Result(target.name(), parameters, loans.size(), excluded, exposure, totals, lower, upper, highBase, byState,
                List.copyOf(loans), options.kind() == GranuleKind.SCENARIO_SET ? DecisionService.METHOD_SCENARIO_SET
                : DecisionService.METHOD_PERTURBATION);
    }
}
