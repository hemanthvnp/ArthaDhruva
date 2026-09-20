# Benchmarks

JMH, JDK 25, single fork, 3 warmup + 5 measured iterations, `-prof gc`. Reproduce:
`mvnw test-compile dependency:build-classpath` then run `com.arthadhruva.riskengine.bench.FeatureBuildBenchmark`.

| Feature-vector build (PD model, 16 features) | Time | Allocation |
|---|---|---|
| Before: per-request `Map<String,Float>` + `List.contains` per feature | ~724 ns/op | 1008 B/op |
| After: `FeatureVectorBuilder`, layout precomputed at startup | ~137 ns/op | 80 B/op |

About 5x faster and 12x less garbage per request; the setup step asserts both produce identical
vectors. This is a microbenchmark of one stage: ONNX inference dominates end-to-end latency, so the
larger win is the reduced allocation pressure (less GC on a small-heap container), not request latency.
