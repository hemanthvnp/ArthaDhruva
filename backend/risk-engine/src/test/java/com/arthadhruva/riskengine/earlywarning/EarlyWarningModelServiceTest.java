package com.arthadhruva.riskengine.earlywarning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain instantiation, no Spring context and no infra (Postgres/Redis/Neo4j) needed --
 * EarlyWarningModelService only reads classpath resources. Expected values below were computed
 * independently in Python from the exact same trained_checkpoint.joblib (model + isotonic
 * calibrator + category_mappings) that produced the exported ONNX/JSON artifacts, for four real
 * rows sampled from the natural-rate calibration holdout -- this is a byte-for-byte cross-check
 * that the Java-side ONNX inference + category/boolean encoding + calibration reproduces the
 * Python training pipeline's output, not just a smoke test.
 */
class EarlyWarningModelServiceTest {

    @Test
    void scoreMatchesPythonReferenceValues() throws Exception {
        EarlyWarningModelService service = new EarlyWarningModelService();

        assertScore(service,
                new EarlyWarningFeatures(
                        726, 37.0, 294000.0, 95.0, 95.0, 5.5, 360, 1, 1, 30.0,
                        15, 96.0, 5.5, 0.01999482993197277, -1.876,
                        4.0, 0.0035494897959184035, -0.06399999999999961,
                        1.0, 0.007050612244898002, -0.14100000000000001,
                        "P", "SF", "P", "R", "Y", "MI", "calm",
                        false, false, false, false),
                0.28680917260835365, 0.048283261802575105);

        assertScore(service,
                new EarlyWarningFeatures(
                        740, 41.0, 168000.0, 91.0, 91.0, 4.875, 360, 2, 1, 25.0,
                        18, 84.0, 4.875, 0.0316567261904761, -1.4249999999999998,
                        -3.0, 0.004034583333333286, -0.27,
                        -5.0, 0.01137964285714288, -0.1549999999999998,
                        "P", "SF", "N", "R", "N", "PA", "calm",
                        false, false, false, false),
                0.3576120655198385, 0.06746987951807229);

        assertScore(service,
                new EarlyWarningFeatures(
                        762, 40.0, 209000.0, 95.0, 95.0, 4.625, 360, 1, 1, 30.0,
                        11, 93.0, 4.625, 0.009697211538461481, -1.02,
                        -2.0, 0.004016971153846138, -0.1974999999999998,
                        -3.0, 0.009697211538461481, -0.6599999999999997,
                        "P", "SF", "P", "C", "Y", "FL", "calm",
                        false, false, false, false),
                0.07495100694553244, 0.00798747908683685);

        assertScore(service,
                new EarlyWarningFeatures(
                        664, 35.0, 177000.0, 77.0, 77.0, 5.0, 180, 1, 1, 0.0,
                        31, 52.0, 5.0, 0.15587802259887007, -2.0375,
                        -5.0, 0.013010225988700497, 0.15249999999999986,
                        -6.0, 0.030768474576271077, 0.1974999999999998,
                        "P", "SF", "C", "R", "N", "NM", "stressed",
                        false, false, false, false),
                0.10054426024980463, 0.011090942397042416);
    }

    private void assertScore(EarlyWarningModelService service, EarlyWarningFeatures loan,
                              double expectedRaw, double expectedCalibrated) {
        EarlyWarningResponse response = service.score(loan);
        // Float32 ONNX round-trip vs. float64 sklearn -- allow a small tolerance, not exact equality.
        assertEquals(expectedRaw, response.rawRisk(), 1e-4, "rawRisk mismatch");
        assertEquals(expectedCalibrated, response.calibratedRisk(), 1e-4, "calibratedRisk mismatch");
    }
}
