## Context

`SurvivalController.compare` projects one loan under up to five scenarios in one batched model call and returns a `ScenarioCurve` per scenario, each with a `TermStructureEngine.Summary` (PDs) and `Ecl` (stage, 12-month, lifetime, IFRS 9, CECL). A portfolio run stores one `Snapshot` row per scenario (`exposure`, `pd12m`, `pdLifetime`, `ecl12m`, `eclLifetime`, `eclIfrs9`, `stage1..3`, model version, date). Nothing combines scenarios.

Constraints that shape the design: new behaviour is additive and opt-in (a request that does not ask must be byte-identical); `TermStructureEngine`, the survival artifact and its golden contract are not modified; engines are exact or seed-reproducible; tenant isolation is Postgres RLS, so any read goes through the existing tenant-scoped service.

## Goals / Non-Goals

**Goals:**
- One probability-weighted set of figures for a single-loan comparison and for the latest portfolio run.
- No new projection, no migration, no change to any model artifact or engine class.
- Deterministic: the same request gives the same bits whatever the order of the weights.

**Non-Goals:**
- Estimating the weights. They are the caller's judgement and are echoed in every response.
- Staging on a probability-weighted PD (see D2).
- Weighted loan-level lists, weighted run-off series, or a weighted loss distribution. The portfolio response is the headline allowance only.
- Storing weights with a run, or a weighted history.

## Decisions

**D1. A pure combiner, `survival/ScenarioWeights`.** One final class with two static entry points: `resolve(Map<String, Double> weights, List<String> scenarios)` returns the weights as a `double[]` aligned with the compared scenarios (trim, upper-case, reject unknown names, negatives, non-finite values, an empty map and a sum outside 1 ± 1e-6), and `combine(double[] w, double[] figure)` returns the ordered sum. Controllers call it; it has no Spring dependency and is unit-tested on its own. Alternative considered: putting the sums inside `TermStructureEngine` or `PortfolioRiskEngine`. Rejected: the engines stay untouched, and the arithmetic needs nothing they hold.

**D2. Weight each scenario's own staged figure; do not re-stage on a weighted PD.** `weighted.eclIfrs9 = sum_s w_s x eclIfrs9_s`, where each `eclIfrs9_s` already reflects the stage the loan is in under scenario `s`. The alternative is to compute a weighted 12-month PD, apply the significant-increase test to it once, and then take the weighted 12-month or lifetime ECL. That is closer to how some lenders stage, but it needs the staging rule outside the engine, cannot be done for a portfolio from stored snapshots (staging is per loan, and only the baseline keeps loan-level results), and makes the weighted figure a non-linear function of the scenarios. The chosen method is linear, exact from what is stored, identical for a loan and a portfolio, and its meaning is plain: "the allowance averaged over the scenarios, each staged on its own path". The response carries stage probabilities so a reader sees when the scenarios disagree on the stage, and the method label `SCENARIO_WEIGHTED` so a different method can be added later without ambiguity.

**D3. Portfolio weighting is a read over stored snapshots.** `POST /v1/risk/portfolio/weighted` reads the latest snapshot per scenario through the existing tenant-scoped `PortfolioRiskService` read and combines them. A POST because it has a body, not because it writes; it is kept out of the audit trail like the other portfolio reads if that annotation is what they use. Alternative considered: a `weights` field on `RunRequest`, stored with the run. Rejected: it would need a column, couple a judgement to a long job, and force a rerun to try other weights. Snapshots of the same date must share a model version; if the stored scenarios come from different runs or versions the endpoint refuses with 409 instead of mixing them.

**D4. Ordered summation, no compensation.** At most five terms of the same sign and similar magnitude; a plain left-to-right sum in compared-scenario order is exact enough (relative error below 1e-15) and is what makes the result independent of the map's key order. A weight of exactly 1 on one scenario and 0 on the rest returns that scenario's figure bit-for-bit, which the spec requires: terms with weight 0 are skipped, not added as `0 * x`, so an infinite or NaN figure in an unweighted scenario cannot leak in.

**D5. The preset lives in the console, not the API.** 50 / 30 / 20 is an illustration; putting it in the API would make it look like the platform's view of the economy. The API has no default weights.

**D6. Response shape.** `Weighted(String method, Map<String, Double> weights, double pd12m, double pdLifetime, double ecl12m, double eclLifetime, double eclIfrs9, double eclCecl, double[] stageProbability)` on `CompareResponse`, `@JsonInclude(NON_NULL)` like `decision`. The portfolio response is its own record: weights, as-of date, model version, exposure, `ecl12m`, `eclLifetime`, `eclIfrs9`, `coverage`, `stageLoans[3]` (weighted counts, fractional) and the scenarios it was built from. CECL is not stored per snapshot in the headline columns, so it is read from the snapshot detail if present and omitted otherwise.

## Risks / Trade-offs

- [A weighted allowance looks more authoritative than its inputs] → the weights are echoed, the preset is labelled illustrative, the console line and `MODELS.md` say the weights are the user's and that the stress scenarios are illustrative, not a regulator's.
- [The stacking limitation carries into the weighted figure: forced stress months plus a price fall is a pair no training month contains] → `MODELS.md` cross-references that limitation from the new section.
- [Staging method differs from a lender's own] → method label in the response, D2 documented, stage probabilities shown.
- [Stale snapshots: the portfolio changed after the run] → the response carries the run's date and loan count, as the portfolio view already does.

## Migration Plan

Additive. Deploy backend then frontend; no data migration. Rollback is removing the field and the endpoint; nothing persisted depends on them.

## Open Questions

- Whether `eclCecl` is present in the stored snapshot detail for every scenario; settled at implementation by reading `PortfolioRiskService`, with the fallback in D6.
