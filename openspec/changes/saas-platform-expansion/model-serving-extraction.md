# Evaluation: extracting model serving into its own service

**Question.** Should `score`, `earlywarning` and `trajectory` inference move out of the monolith into an
independently deployed model-serving service?

**Recommendation today: no.** Keep in-process serving. Extract only when one of the triggers below is
actually met. Extracting earlier buys a network hop per score, a second deployable, and a second
failure mode for no measured benefit.

## What in-process serving costs and buys (measured, see PERFORMANCE.md)

- Inference is microseconds of native ONNX work per call; a mixed workload on two 1.5-CPU replicas
  sustained ~109 req/s at 0.27 s average latency.
- No network hop, no serialization boundary, no extra service to run, secure, or trace.
- The scaling unit is the whole backend replica, so a scoring spike also scales case search and auth.

## Triggers: extract when ANY of these is true

Thresholds are deliberately concrete so the decision is a measurement, not an opinion.

| # | Trigger | Threshold | How to measure |
|---|---|---|---|
| 1 | Scoring dominates CPU | Score endpoints take > 60% of total backend CPU at peak for a week, and replicas are CPU-bound (> 70% of limit) | Prometheus: CPU by endpoint (`http_server_requests` x per-endpoint CPU profile) |
| 2 | Scoring latency SLO at risk | Score p95 > 400 ms (the CI gate) while search/auth stay well under their gates, i.e. scoring is what is being starved | k6 gate in CI plus Grafana per-endpoint p95 |
| 3 | Model memory | Loaded model artifacts exceed 25% of a replica's memory limit, or more than ~10 models | JVM heap / native memory per replica |
| 4 | Independent release cadence | Models are retrained/redeployed more than weekly while application code ships less often (or the reverse), and the coupling forces unwanted rollouts | Release log |
| 5 | Different hardware | A model needs a GPU or a specialised runtime the API replicas should not carry | Model requirements |
| 6 | Different language/runtime | A model cannot be exported to ONNX and needs a Python runtime | Model requirements |

Trigger 1 and 2 are the performance triggers; 3-6 are organisational/technical constraints.

## If triggered: the shape of the extraction

1. Define the boundary at the existing facade methods (`ModelService.score`, etc.). Callers already go
   through them, so the seam exists; only the implementation changes.
2. Introduce an interface `ScoringClient` with the current in-process implementation and a new HTTP/gRPC
   implementation; select by configuration so both can run during migration.
3. Wrap the remote implementation in the existing Resilience4j pattern (circuit breaker, bulkhead,
   timeout, retry with jitter) and decide the failure semantics: scoring is a synchronous user action, so
   fail loudly with 503 rather than fall back to a stale score.
4. Keep tenant isolation intact: the serving service is stateless and receives only features, never
   tenant data; the tenant id stays with the caller for persistence and metering.
5. Scale it independently, behind the same nginx/service-discovery pattern as the backend replicas.

## What would NOT be a reason

- "Microservices are the modern way." The modular monolith with facades was a deliberate choice
  (design.md, Goals/Non-Goals); a boundary with no measured pressure is cost without benefit.
- Explanation cost alone: it multiplies inference count by ~17 per score, but that is cheap in-process
  and would be far more expensive across a network. It argues for keeping serving in-process.
