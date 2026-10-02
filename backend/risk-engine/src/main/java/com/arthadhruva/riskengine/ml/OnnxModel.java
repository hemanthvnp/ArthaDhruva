package com.arthadhruva.riskengine.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * One ONNX classifier loaded from the classpath, evaluated in batches: a whole term structure (every
 * future month under every regime) or every Shapley coalition is a single {@code run}, not hundreds.
 * Records the artifact's SHA-256 so every result can be traced to the exact bytes that produced it.
 */
public final class OnnxModel implements AutoCloseable {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String sha256;
    private final int features;

    private OnnxModel(OrtEnvironment environment, OrtSession session, String sha256, int features) {
        this.environment = environment;
        this.session = session;
        this.sha256 = sha256;
        this.features = features;
    }

    /** @param intraOpThreads threads one inference may use; 1 for request paths (concurrency comes from
     *                        request threads), more only for batch jobs that own their cores */
    public static OnnxModel load(String resource, int features, int intraOpThreads) throws IOException, OrtException {
        byte[] bytes;
        try (InputStream is = OnnxModel.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + resource);
            }
            bytes = is.readAllBytes();
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(intraOpThreads);
        options.setInterOpNumThreads(1);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        options.addConfigEntry("session.inter_op.allow_spinning", "0");
        OrtSession session = env.createSession(bytes, options);
        return new OnnxModel(env, session, sha256(bytes), features);
    }

    public String sha256() {
        return sha256;
    }

    public int features() {
        return features;
    }

    /** Class probabilities for {@code rows} feature vectors laid out row-major in {@code flat}. */
    public float[][] probabilities(float[] flat, int rows) {
        if (flat.length != rows * features) {
            throw new IllegalArgumentException("Expected " + rows * features + " values, got " + flat.length);
        }
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), new long[]{rows, features});
             OrtSession.Result result = session.run(Map.of("input", input))) {
            return (float[][]) result.get(1).getValue();
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
