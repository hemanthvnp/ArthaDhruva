# Performance and cost notes

Everything here was measured on one Docker Desktop host (24 logical cores shared with other
containers), so treat the absolute numbers as indicative and the *ratios* as the finding. Load test:
k6, constant 120 req/s arrival rate for 25 s against two backend replicas (1.5 CPU / 1 GiB each), a
40 / 40 / 20 mix of score, case search and catalog reads. Script: [loadtest/smoke.js](loadtest/smoke.js).

## 1. The bottleneck was ONNX Runtime's thread pool, not the code

First run: the stack completed only ~30 req/s with 3.5 s average latency and 7% errors, both replicas
pinned at their CPU limit while Postgres sat near idle. Trace sampling and JIT flags made no
difference, which ruled them out; the CPU was going somewhere structural.

ONNX Runtime sizes its intra-op thread pool to the *host's* core count (24 here) and its idle threads
spin-wait. Under a 1.5-CPU cgroup quota that is two dozen threads burning the entire quota doing
nothing. These are tiny tree models (microseconds per inference), so parallelising one inference buys
nothing. Fix: one intra-op thread, spinning off
([AbstractOnnxModelService](backend/risk-engine/src/main/java/com/arthadhruva/riskengine/ml/AbstractOnnxModelService.java)).

| Trial | Throughput | Avg latency | Errors |
|---|---|---|---|
| Baseline | 30 req/s | 3.5 s | 7% |
| Tracing sampling 0 (baseline JVM) | 30 req/s | 3.5 s | 8% |
| Full tiered JIT (baseline otherwise) | 23 req/s | 4.6 s | 10% |
| **ONNX single-thread, non-spinning** | **97 req/s** | **0.55 s** | 0.03% |
| ONNX fix + tracing sampling 0 | **109 req/s** | **0.27 s** | 0% |
| ONNX fix + sampling 0 + full tiered JIT | 52 req/s | 1.9 s | 0.2% |
| ONNX fix + sampling 0.1 + full JIT + ParallelGC | 66 req/s | 1.7 s | 0% |

Roughly 3.6x throughput and 13x lower average latency from one setting. Two more findings:

- **C1-only JIT (`-XX:TieredStopAtLevel=1`) with SerialGC beats full tiered compilation** on a small,
  CPU-quota'd, short-lived container: the C2 compiler's warm-up work competes with request handling
  for the same 1.5 CPUs. A long-running instance with spare cores would flip this; measure before
  changing. That is why the image ships `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k
  -XX:MaxRAMPercentage=60`.
- Tracing every request cost a measurable ~13% throughput; the default sample rate is now 10%
  (`TRACE_SAMPLING`, see application.properties).

## 2. Algorithmic and query fixes (time/space complexity)

| Change | Before | After |
|---|---|---|
| Feature-vector build ([BENCHMARKS.md](backend/risk-engine/BENCHMARKS.md)): per-request boxed map + `List.contains` per feature, O(F^2) | 724 ns, 1008 B/op | 137 ns, 80 B/op |
| State to loan-ids lookup: scan of the whole catalog | O(n) per request | O(1), index built once |
| `GET /admin/users`: eager `loanIds` collection loaded per user (N+1) | 1 + N queries (15 for 14 users) | 1 + 1 (subselect) |
| Rate limiting, rules, metering: plan lookup per request | DB round trip | cached 60 s per tenant |

Other places checked and found already fine: the early-warning and trajectory catalogs are map-backed
O(1) lookups; the isotonic calibrator binary-searches its breakpoints.

## 3. Index review (EXPLAIN ANALYZE at 200,000 rows in one tenant, as the app role under RLS)

Every "most recent N" list was a parallel sequential scan of the whole tenant plus a top-N sort.
[V23](backend/risk-engine/src/main/resources/db/migration/V23__query_indexes.sql) adds an index per
tenant-filter + sort order:

| Query | Before | After |
|---|---|---|
| Recent cases | 83.6 ms (Seq Scan + Sort) | 0.14 ms (Index Scan) |
| Case search (status + flagged, newest first) | 27.1 ms | 2.0 ms |
| Recent notes | 89.5 ms | 0.93 ms |
| Recent scores | 84.5 ms | 0.99 ms |
| Notification inbox (visible rows only, partial index) | 9.9 ms | 0.85 ms |

Already index-driven and left alone: notes for one loan (0.16 ms), the webhook outbox claim (partial
index on undelivered rows), primary-key lookups.

## 4. Sizing and cost

- **Connection pool:** Little's law. Busy connections = requests/s x DB time per request; at 200 req/s
  and ~20 ms that is ~4, so `maximum-pool-size=10` per replica leaves headroom while N replicas x 10
  stays well under Postgres' 100 connections. Tomcat threads are capped at 60 (`TOMCAT_MAX_THREADS`)
  so overload queues briefly and then fails fast instead of piling up threads on a 10-connection pool.
- **Footprint:** a backend replica idles at ~485 MiB of its 1 GiB limit; Postgres ~100 MiB, Redis ~5
  MiB, Neo4j ~385 MiB. The whole stack fits in ~2.5 GiB. Everything runs under CPU and memory limits
  so it cannot starve its neighbours.
- **Throughput per unit of compute:** ~109 mixed req/s on 3 CPUs allotted, ~36 req/s per CPU. Score
  (17 in-process inferences with the explanation) is the most expensive call, which is the point of
  the extraction criteria in
  [model-serving-extraction.md](openspec/changes/saas-platform-expansion/model-serving-extraction.md).
- **Caveats:** short 25 s runs on a shared desktop host; not a steady-state or soak result. The CI
  load test gates on latency thresholds and error rate, not on these absolute throughput numbers.
