package com.arthadhruva.riskengine.trajectory;

import ai.onnxruntime.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Serves the LSTM trajectory model (exported by backend/export_lstm_model.py from
 * lstm_trajectory_model.ipynb) in-process via ONNX Runtime, same pattern as
 * {@link com.arthadhruva.riskengine.score.ModelService}. Predicts near-term default probability
 * from a loan's first up to 12 months of actual performance, not a static origination-time
 * snapshot.
 *
 * The exported graph has no packing/masking built in -- it expects exactly the caller's real
 * months with no padding (see export_lstm_model.py's parity check for why this is equivalent to
 * the packed-sequence model it was trained as), so feature-building here must match the export
 * script's preprocessing exactly: status parsed-or-sentinel-12, UPB ratio clipped to [0, 5], both
 * standardized with the exported train-set mean/std; the modification flag stays raw 0/1.
 */
@Service
public class TrajectoryModelService {

    private static final int STATUS_SENTINEL = 12;
    private static final double UPB_RATIO_MIN = 0.0;
    private static final double UPB_RATIO_MAX = 5.0;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final double[] featMean;
    private final double[] featStd;

    public TrajectoryModelService() throws OrtException, IOException {
        this.environment = OrtEnvironment.getEnvironment();

        byte[] modelBytes = readResource("lstm_model.onnx");
        this.session = environment.createSession(modelBytes, new OrtSession.SessionOptions());

        ObjectMapper mapper = new ObjectMapper();
        LstmMeta meta = mapper.readValue(readResource("lstm_meta.json"), LstmMeta.class);
        this.featMean = meta.featMean();
        this.featStd = meta.featStd();
    }

    private byte[] readResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    public TrajectoryScoreResponse score(TrajectoryRequest request) {
        float[] flatInput = buildFeatureTensor(request);
        int seqLen = request.months().size();
        try {
            OnnxTensor input = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(flatInput), new long[]{1, seqLen, 3});
            try (OrtSession.Result result = session.run(Collections.singletonMap("input", input))) {
                float[][] logitOutput = (float[][]) result.get(0).getValue();
                double logit = logitOutput[0][0];
                double probability = 1.0 / (1.0 + Math.exp(-logit));
                return new TrajectoryScoreResponse(probability);
            } finally {
                input.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    private float[] buildFeatureTensor(TrajectoryRequest request) {
        double originalUpb = request.originalUpb() <= 0 ? 1.0 : request.originalUpb();
        List<MonthlyRecord> months = request.months();
        float[] flat = new float[months.size() * 3];

        for (int i = 0; i < months.size(); i++) {
            MonthlyRecord month = months.get(i);

            double statusNumeric = parseStatusOrSentinel(month.currentLoanDelinquencyStatus());
            double upbRatio = clip(month.currentActualUpb() / originalUpb, UPB_RATIO_MIN, UPB_RATIO_MAX);
            double isModified = "Y".equals(month.modificationFlag()) ? 1.0 : 0.0;

            flat[i * 3] = (float) ((statusNumeric - featMean[0]) / featStd[0]);
            flat[i * 3 + 1] = (float) ((upbRatio - featMean[1]) / featStd[1]);
            flat[i * 3 + 2] = (float) isModified;
        }
        return flat;
    }

    private double parseStatusOrSentinel(String status) {
        try {
            return Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            return STATUS_SENTINEL;
        }
    }

    private double clip(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @PreDestroy
    public void close() throws OrtException {
        session.close();
        environment.close();
    }

    private record LstmMeta(
            @JsonProperty("feat_mean") double[] featMean,
            @JsonProperty("feat_std") double[] featStd
    ) {
    }
}
