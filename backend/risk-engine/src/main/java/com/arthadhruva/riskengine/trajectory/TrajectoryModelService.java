package com.arthadhruva.riskengine.trajectory;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.arthadhruva.riskengine.ml.AbstractOnnxModelService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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
 *
 * <p>Unlike {@code ModelService}/{@code EarlyWarningModelService}, this model has no isotonic
 * calibration step -- the sigmoid of its raw logit is used directly -- so {@link #calibrate} is
 * left at the template's identity default.
 */
@Service
public class TrajectoryModelService extends AbstractOnnxModelService<TrajectoryRequest, TrajectoryScoreResponse> {

    private static final int STATUS_SENTINEL = 12;
    private static final double UPB_RATIO_MIN = 0.0;
    private static final double UPB_RATIO_MAX = 5.0;
    private static final int FEATURES_PER_MONTH = 3;

    private final double[] featMean;
    private final double[] featStd;

    public TrajectoryModelService() throws OrtException, IOException {
        super();
        ObjectMapper mapper = new ObjectMapper();
        LstmMeta meta = mapper.readValue(readResource("lstm_meta.json"), LstmMeta.class);
        this.featMean = meta.featMean();
        this.featStd = meta.featStd();
    }

    @Override
    protected String modelResourceName() {
        return "lstm_model.onnx";
    }

    @Override
    protected long[] tensorShape(float[] featureVector) {
        return new long[]{1, featureVector.length / FEATURES_PER_MONTH, FEATURES_PER_MONTH};
    }

    @Override
    protected double extractRawProbability(OrtSession.Result result) throws OrtException {
        float[][] logitOutput = (float[][]) result.get(0).getValue();
        double logit = logitOutput[0][0];
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    @Override
    protected TrajectoryScoreResponse buildResponse(double rawProbability, double calibratedProbability) {
        return new TrajectoryScoreResponse(calibratedProbability);
    }

    @Override
    protected float[] buildFeatureVector(TrajectoryRequest request) {
        double originalUpb = request.originalUpb() <= 0 ? 1.0 : request.originalUpb();
        List<MonthlyRecord> months = request.months();
        float[] flat = new float[months.size() * FEATURES_PER_MONTH];

        for (int i = 0; i < months.size(); i++) {
            MonthlyRecord month = months.get(i);

            double statusNumeric = parseStatusOrSentinel(month.currentLoanDelinquencyStatus());
            double upbRatio = clip(month.currentActualUpb() / originalUpb, UPB_RATIO_MIN, UPB_RATIO_MAX);
            double isModified = "Y".equals(month.modificationFlag()) ? 1.0 : 0.0;

            flat[i * FEATURES_PER_MONTH] = (float) ((statusNumeric - featMean[0]) / featStd[0]);
            flat[i * FEATURES_PER_MONTH + 1] = (float) ((upbRatio - featMean[1]) / featStd[1]);
            flat[i * FEATURES_PER_MONTH + 2] = (float) isModified;
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

    private record LstmMeta(
            @JsonProperty("feat_mean") double[] featMean,
            @JsonProperty("feat_std") double[] featStd
    ) {
    }
}
