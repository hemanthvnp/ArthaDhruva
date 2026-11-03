package com.arthadhruva.riskengine.survival;

import ai.onnxruntime.OrtException;
import com.arthadhruva.riskengine.ml.OnnxModel;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The delinquency-aware transition model (backend/export_delinquency_model.py): for one loan-month, from each active
 * state (current, 30, 60 days past due), the probability of each next state (current, 30, 60, default, prepaid).
 *
 * <p>Its inputs are the survival model's features, in the same order, then the source state and three history
 * features, so a row is a survival row plus four columns and {@link SurvivalModel#writeRow} builds the first part. It is
 * a separate artifact with its own card and checksum; the survival model is not touched, and the service refuses to
 * start if the artifact does not match its card.
 */
@Component
public class DelinquencyModel {

    public static final String ID = "delinquency";
    public static final int CURRENT = 0;
    public static final int D30 = 1;
    public static final int D60 = 2;
    /** Classes after the three active states: default and prepaid. */
    public static final int DEFAULT = 3;
    public static final int PREPAID = 4;
    /** Active (source) states. */
    public static final int STATES = 3;
    public static final int CLASSES = 5;
    /** Columns after the survival row: the source state and the three history features. */
    static final int EXTRA = 4;

    /**
     * A loan's history before the projection, as the model's features: 30+ day episodes that began in the last 12
     * months, months since the latest cure ({@link #noCureMonths()} when none in 12 months) and the worst state in the
     * last 12 months (0 current, 1 30 days, 2 60 days).
     */
    public record History(int episodes, int monthsSinceCure, int maxState) {
    }

    private final OnnxModel onnx;
    private final JsonNode card;
    private final String version;
    private final int survivalWidth;
    private final int noCureMonths;
    private final double probabilityFloor;
    private final Map<String, SurvivalModel.Range> envelope = new LinkedHashMap<>();

    public DelinquencyModel(SurvivalModel survival) throws IOException, OrtException {
        ObjectMapper mapper = new ObjectMapper();
        this.card = mapper.readTree(read("delinquency_model.json"));
        this.survivalWidth = survival.features();
        List<String> order = new ArrayList<>();
        card.get("features_in_order").forEach(f -> order.add(f.asString()));
        List<String> survivalOrder = new ArrayList<>();
        survival.metadata().get("features_in_order").forEach(f -> survivalOrder.add(f.asString()));
        if (!order.subList(0, survivalWidth).equals(survivalOrder)
                || !order.subList(survivalWidth, order.size()).equals(
                        List.of("source_state", "hist_episodes_12m", "hist_months_since_cure", "hist_max_state_12m"))) {
            throw new IllegalStateException("delinquency_model.json: the first features must be the survival model's, "
                    + "followed by source_state and the three history features");
        }
        this.onnx = OnnxModel.load("delinquency_model.onnx", order.size(), 1);
        if (!onnx.sha256().equals(card.get("artifact_sha256").asString())) {
            throw new IllegalStateException("delinquency_model.onnx does not match the checksum in delinquency_model.json");
        }
        this.version = ID + "@" + card.get("version").asString();
        this.noCureMonths = card.get("no_cure_months").asInt();
        this.probabilityFloor = card.get("probability_floor").get("value").asDouble();
        for (Map.Entry<String, JsonNode> e : card.get("training").get("envelope").properties()) {
            envelope.put(e.getKey(), new SurvivalModel.Range(e.getValue().get(0).asDouble(), e.getValue().get(1).asDouble()));
        }
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream is = DelinquencyModel.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    public String version() {
        return version;
    }

    public String artifactSha256() {
        return onnx.sha256();
    }

    public JsonNode card() {
        return card;
    }

    /** The value of {@code hist_months_since_cure} that means no cure in the last 12 months. */
    public int noCureMonths() {
        return noCureMonths;
    }

    /** Columns of a row: the survival model's, then {@link #EXTRA}. */
    int width() {
        return survivalWidth + EXTRA;
    }

    /** Training range of a time-varying driver, for this model's own training rows. */
    public SurvivalModel.Range envelope(String driver) {
        SurvivalModel.Range range = envelope.get(driver);
        if (range == null) {
            throw new IllegalArgumentException("No training envelope for " + driver);
        }
        return range;
    }

    /** Writes the row for {@code source} state into {@code flat} at row {@code row}, from a survival-width row. */
    void writeRow(float[] flat, int row, float[] survivalRows, int survivalRow, int source, History history) {
        int at = row * width();
        System.arraycopy(survivalRows, survivalRow * survivalWidth, flat, at, survivalWidth);
        flat[at + survivalWidth] = source;
        flat[at + survivalWidth + 1] = history.episodes();
        flat[at + survivalWidth + 2] = history.monthsSinceCure();
        flat[at + survivalWidth + 3] = history.maxState();
    }

    /**
     * Next-state probabilities for {@code rows} rows, as a flat array of {@code rows x 5}: renormalized in double (the
     * graph's float32 output sums to 1 only to about 1e-7), and, from a delinquent source state, floored at the card's
     * {@code probability_floor} and renormalized again, the same steps as backend/golden_multistate.py.
     */
    double[] probabilities(float[] flat, int rows) {
        float[][] p = onnx.probabilities(flat, rows);
        double[] out = new double[rows * CLASSES];
        int width = width();
        for (int i = 0; i < rows; i++) {
            boolean floored = flat[i * width + survivalWidth] > 0;
            double sum = 0;
            for (int c = 0; c < CLASSES; c++) {
                double v = p[i][c];
                sum += v;
                out[i * CLASSES + c] = v;
            }
            double total = 0;
            for (int c = 0; c < CLASSES; c++) {
                double v = out[i * CLASSES + c] / sum;
                v = floored ? Math.max(v, probabilityFloor) : v;
                out[i * CLASSES + c] = v;
                total += v;
            }
            for (int c = 0; c < CLASSES; c++) {
                out[i * CLASSES + c] /= total;
            }
        }
        return out;
    }

    /** Classes ordered from best to worst (prepaid, current, 30, 60, default), as indices into the class order. */
    private static final int[] BEST_TO_WORST = {PREPAID, CURRENT, D30, D60, DEFAULT};

    /**
     * Makes each month's transition probabilities stochastically ordered by source state, in place: {@code p} holds five
     * probabilities per model row, and every {@code STATES} consecutive rows are the same month and regime evaluated from
     * the current, 30 and 60 days past due states. With the classes ordered prepaid < current < 30 < 60 < default, a
     * worse source state must have no smaller probability of landing at or above any class than a better one; where the
     * model's raw output breaks that, each upper-tail probability is replaced by its running maximum over the source
     * states. Valid probabilities result (non-negative, summing to 1), unchanged where the order already holds. It is
     * what guarantees lifetime default does not fall as the starting state worsens; backend/delinquency_history.py
     * has the argument and the same function.
     */
    static void monotoneRepair(double[] p, int rows) {
        if (rows % STATES != 0) {
            throw new IllegalArgumentException("model rows come in groups of " + STATES + " source states");
        }
        double[][] tail = new double[STATES][CLASSES];
        for (int first = 0; first < rows; first += STATES) {
            for (int s = 0; s < STATES; s++) {
                int base = (first + s) * CLASSES;
                tail[s][CLASSES - 1] = p[base + BEST_TO_WORST[CLASSES - 1]];
                for (int k = CLASSES - 2; k >= 0; k--) {
                    tail[s][k] = tail[s][k + 1] + p[base + BEST_TO_WORST[k]];
                }
            }
            for (int s = 1; s < STATES; s++) {
                for (int k = 0; k < CLASSES; k++) {
                    tail[s][k] = Math.max(tail[s][k], tail[s - 1][k]);
                }
            }
            for (int s = 0; s < STATES; s++) {
                int base = (first + s) * CLASSES;
                for (int k = 0; k < CLASSES; k++) {
                    p[base + BEST_TO_WORST[k]] = k + 1 < CLASSES ? tail[s][k] - tail[s][k + 1] : tail[s][k];
                }
            }
        }
    }

    @PreDestroy
    void close() throws OrtException {
        onnx.close();
    }
}
