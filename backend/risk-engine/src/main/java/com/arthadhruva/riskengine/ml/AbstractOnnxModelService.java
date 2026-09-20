package com.arthadhruva.riskengine.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Template Method (design decision 8) for the load-model / build-feature-vector /
 * run-ONNX-inference / calibrate skeleton shared by {@code score.ModelService}, {@code
 * earlywarning.EarlyWarningModelService}, and {@code trajectory.TrajectoryModelService} -- this
 * used to be three near-identical copies of {@link #score}'s try/finally/exception-wrapping and
 * session lifecycle. Each subclass supplies only what's genuinely specific to its model: how to
 * turn its own feature type into a flat vector, what tensor shape that vector represents, how to
 * pull a raw probability out of that model's particular ONNX output shape, and (for the two
 * PD-style models) an isotonic calibration correction -- {@link #calibrate} defaults to the
 * identity function for models (like the trajectory LSTM) that don't calibrate at all.
 *
 * @param <TFeatures> the model-specific input type
 * @param <TResponse> the model-specific response type
 */
public abstract class AbstractOnnxModelService<TFeatures, TResponse> {

    protected final OrtEnvironment environment;
    protected final OrtSession session;

    protected AbstractOnnxModelService() throws OrtException, IOException {
        this.environment = OrtEnvironment.getEnvironment();
        this.session = environment.createSession(readResource(modelResourceName()), sessionOptions());
    }

    /**
     * Single-threaded, non-spinning inference. These are tiny tree/LSTM models (microseconds of
     * work per call), so parallelising one inference across cores buys nothing, while ONNX Runtime's
     * default is an intra-op pool sized to the HOST's core count whose idle threads spin-wait: in a
     * container limited to ~1.5 CPUs that is dozens of threads burning the whole quota doing nothing
     * (measured: it capped throughput at ~30 req/s with the CPU pinned). Request-level concurrency
     * comes from the servlet threads instead.
     */
    private static OrtSession.SessionOptions sessionOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        options.addConfigEntry("session.inter_op.allow_spinning", "0");
        return options;
    }

    /** Classpath resource name of this model's exported ONNX graph. */
    protected abstract String modelResourceName();

    /** Turns model-specific features into the flat vector ONNX Runtime expects. */
    protected abstract float[] buildFeatureVector(TFeatures features);

    /** The tensor shape (including the leading batch dimension of 1) for a built feature vector. */
    protected abstract long[] tensorShape(float[] featureVector);

    /** Pulls this model's raw (uncalibrated) probability out of its ONNX output -- output shape
     * and encoding (dense tensor vs. ZipMap, classifier probability vs. a logit needing a
     * sigmoid) is genuinely model-specific and not worth forcing into a shared shape. */
    protected abstract double extractRawProbability(OrtSession.Result result) throws OrtException;

    /** Identity by default; PD-style models override this with their isotonic calibrator. */
    protected double calibrate(double rawProbability) {
        return rawProbability;
    }

    /** Assembles this model's own response type from the raw/calibrated pair. */
    protected abstract TResponse buildResponse(double rawProbability, double calibratedProbability);

    public final TResponse score(TFeatures features) {
        float[] featureVector = buildFeatureVector(features);
        try {
            OnnxTensor input = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(featureVector), tensorShape(featureVector));
            try (OrtSession.Result result = session.run(Collections.singletonMap("input", input))) {
                double rawProbability = extractRawProbability(result);
                double calibratedProbability = calibrate(rawProbability);
                return buildResponse(rawProbability, calibratedProbability);
            } finally {
                input.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    /** Calibrated probability for an already-built feature vector: the same inference path as
     * {@link #score}, exposed so callers can probe the model with modified vectors (attribution). */
    public final double calibratedProbabilityOf(float[] featureVector) {
        try {
            OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(featureVector), tensorShape(featureVector));
            try (OrtSession.Result result = session.run(Collections.singletonMap("input", input))) {
                return calibrate(extractRawProbability(result));
            } finally {
                input.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    /** Available to subclasses for their own extra classpath resources (category mappings,
     * feature order, calibration breakpoints, LSTM normalization stats, ...). */
    protected final byte[] readResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + name);
            }
            return is.readAllBytes();
        }
    }

    @PreDestroy
    public void close() throws OrtException {
        session.close();
        environment.close();
    }
}
