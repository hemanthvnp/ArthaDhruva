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

## Risk engines

`com.arthadhruva.riskengine.bench.RiskEngineBenchmark`, on the real model artifacts. JMH, JDK 25, single
fork, 3 warm-up and 5 measured iterations of 2 s, on an otherwise idle machine. One core unless a thread
count is given.

| Computation | Time |
|---|---|
| PD score (one inference and its calibration) | 0.030 ms |
| Shapley explanation of that score (64 antithetic orderings, one batched model call) | 6.6 ms |
| One loan's term structure to maturity (360 months in both regimes: 720 model rows) | 52.6 ms |
| The same loan under all five scenarios, one model call | 258.8 ms |
| Loss simulation, 1,000 loans x 20,000 scenarios, 1 thread | 145 ms |
| ... on 4 threads | 53 ms |
| ... with the tail attributed to loans (the replay pass), 1 thread / 4 threads | 212 ms / 68 ms |

What the numbers say:

- **A projection costs about 73 microseconds per model row**, almost all of it inside ONNX Runtime
  (the survival model is 596 boosting rounds of three trees). Five scenarios in one call cost five times
  one scenario: batching removes call overhead, not tree evaluation. That cost is why a portfolio run is
  a background job with progress, and why portfolios above 5,000 loans are sampled.
- **The explanation is 220 times the cost of the score it explains**, and still under 7 ms, because
  every coalition goes through the model in one batch instead of one call each.
- **The simulation draws 20 million correlated default indicators in 145 ms** (7 ns each) and scales
  2.8x on four threads. Attributing the tail to loans adds about half again, by replaying only the tail
  scenarios from their seeds.

### Does the image's JIT setting cost anything here?

The image runs with `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` (C1 only). The same benchmark with those
flags: the term structure takes 52.7 ms, no different, because the time is in native code; the loss
simulation, which is pure Java, takes 205 ms instead of 145 ms. The end-to-end comparison in the
container, which is what decides the setting, is in [PERFORMANCE.md](../../PERFORMANCE.md).

A first run of this comparison reported the term structure 2.8x slower under C1. It was measured while
another build was using the machine and did not reproduce: three reruns gave 52.0 to 52.7 ms under both
settings. Benchmarks here are only quoted from an idle machine.
