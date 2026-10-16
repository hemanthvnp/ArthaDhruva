## Context

Established from the code and the neighbouring change (re-verify before building):

- **Engine surface.** `TermStructureEngine` projects one loan under one `Scenario` (a record of `stressedMonths`, `unemploymentShockPp/Months/RecoveryMonths`, `hpiShockPct/Months`, `hpiAnnualGrowthPct`, `rateShockBp`, all bean-validated) and returns `Summary` (`pd12m`, `pd24m`, `pdLifetime`, ...) and `Ecl` (`eclLifetime`, `eclIfrs9`, `eclCecl`, `exposure`, ...). `projectAll(List<Request>)` evaluates many scenarios in one batched model call. `extrapolations(Outcome)` reports drivers pushed outside the trained range, where the tree model holds its response at the edge.
- **Determinism rules.** Engines are exact or seed-reproducible; `CvarEngine` replays scenario `s` from its own seed so results are identical on any thread count; `PortfolioRiskEngine` ordering and merge determinism must be preserved. Portfolio runs are a resource (202, heartbeat, one active run per org, any replica serves the poll).
- **API shape.** `SurvivalController` serves `/risk/term-structure` and `/risk/term-structure/compare`; `PortfolioRiskController` serves `/risk/portfolio/runs`, `/risk/portfolio`, `/risk/portfolio/loans`. Responses already carry `warnings`.
- **Tenant isolation** is enforced by Postgres RLS; any new tenant table needs policies, and the audit table is append-only for the app role.
- **Neighbouring change** `delinquency-aware-risk-and-uncertainty` plans regime-chain posterior draws and a calibration band. This change must work without it and use its draws when they exist.
- **Contract rules binding this work.** The existing survival artifact, card checksum and golden test stay untouched; no test, security control, model-validation check or the bundle budget is weakened; commits are by the owner only; only report checks that ran.

## Goals / Non-Goals

**Goals:**
- A reproducible three-way classification (certain high, certain low, boundary) of a loan's stressed risk metric against a threshold, with the boundary width reported.
- The same classification for a portfolio, with exposure and expected-loss ranges implied by the regions.
- An honest signal when flat tree extrapolation makes the boundary look narrower than it is.
- Zero behaviour change for callers that send no `decision` block, and no change to any model artifact.

**Non-Goals:**
- Classical rough-set data mining (attribute reduction, rule induction on loan features).
- A coverage guarantee. The regions are a robustness classification, not a probability or a statistical interval.
- Changing any threshold policy, IFRS 9 staging rule, scenario definition or the LightGBM model.
- A new trained artifact, dataset or checksum.

## Decisions

### D1. A pure overlay package calling the engine through its public methods
New package `com.arthadhruva.riskengine.roughset` with a granule builder, a classifier and a portfolio aggregator. It calls `TermStructureEngine.projectAll` and never edits the engine. **Why:** the engine is validated and golden-tested; an overlay cannot break that contract and the card checksum is untouched by construction. **Alternative:** compute regions inside the engine (saves a pass, but couples the validated core to a decision policy and touches the golden path).

### D2. The granule is the neighbourhood of the scenario being decided, not the union of unrelated scenarios
Rough-set approximations need an indiscernibility relation. The defensible one here is: "the same loan under any admissible variant of the scenario I am deciding on". A granule is therefore a set of `n` scenario variants of one target scenario, member 0 being the unperturbed target, the others drawn from a bounded neighbourhood: shock magnitudes (`unemploymentShockPp`, `hpiShockPct`, `rateShockBp`) scaled by a factor in `[1-b, 1+b]` and shock timing (`unemploymentShockMonths`, `hpiShockMonths`, `stressedMonths`) shifted by up to `±t` months, all clamped to the `Scenario` validation ranges. **Why:** putting BASELINE and SEVERELY_ADVERSE in one granule makes the boundary equal to "scenario sensitivity", which is already visible in the compare view and says nothing about whether a decision under one scenario is fragile. **Alternative kept as a second mode:** a `SCENARIO_SET` granule over a caller-chosen list of built-in scenarios (lower approximation = high risk under every listed scenario), useful for "robust across the supervisory set" and labelled as such. When the uncertainty change has landed, regime-chain posterior draws are added as further members of the perturbation granule (`regimeDraws` option); until then the option is rejected with a clear message rather than silently ignored.

### D3. Metric, threshold and direction
The metric is one of `PD_12M`, `PD_LIFETIME`, `ECL_LIFETIME_RATE` (lifetime ECL divided by exposure; the rate keeps loans of different size comparable). A loan is "high" for a member when `metric >= threshold`. The threshold is supplied by the caller and is not a platform policy. **Why rate, not amount, for ECL:** an amount threshold would sort loans by size.

### D4. Classification with a variable-precision tolerance
Let `n` be the granule size and `k` the number of members that are high. With tolerance `beta` in `[0, 0.5)` (default 0): `CERTAIN_HIGH` if `k/n >= 1 - beta`, `CERTAIN_LOW` if `k/n <= beta`, else `BOUNDARY`. With `beta = 0` this is the strict Pawlak lower/upper approximation (certain high = lower approximation of HIGH; certain low = outside its upper approximation). The response reports `k`, `n`, `beta`, the metric's minimum, median and maximum across the granule, and the boundary width (`max - min`). **Why variable precision:** one extreme member should not remove a loan from the decided set; the tolerance is explicit and echoed, never a hidden default. **Alternative:** fuzzy rough sets (a graded membership, richer but harder to explain and to hold to an independent reference).

### D5. Counter-based seeding, identical on any thread count
Member `i` derives its perturbation from `splitmix64(seed + i * GOLDEN_GAMMA)`, with no shared RNG state, mirroring how `CvarEngine` replays scenario `s`. The default seed is a stable hash of the canonicalised request (loan, target scenario, granule options), so the same request returns the same regions, and an explicit `seed` overrides it. **Why:** reproducibility is a repo rule; counter-based generation makes members order-independent so parallel evaluation and merge cannot change the answer.

### D6. Batched evaluation and a bounded cost
Members are evaluated with one `projectAll` call per loan. Defaults: `n = 32` for a single loan, hard cap 128; `n = 8` and a cap of 32 for portfolios. A single-loan request therefore costs about one compare call with `n` scenarios. `PERFORMANCE.md` puts a plain projection near 40 ms in the 1.5-CPU container (1,200 projections in 48 s), but a decision projection measured 114 ms on one worker on a development machine (160 projections in 18.3 s, including the extrapolation check on each variant). A portfolio run is therefore refused when `loans x members` exceeds a configured limit (default 2,000 projections, about four minutes at 114 ms) instead of sampling, and the refusal message gives the product and the limit. Regions are computed for one target scenario per run (default: the most severe scenario requested), not for every scenario, which keeps the cost at `loans x members`, not `scenarios x loans x members`. **Why bounded:** the portfolio cost is `loans x n` model evaluations; the cap and a measured budget (task in the plan) keep a run inside the existing run-time expectations. **Alternative:** share the model rows across members (the macro path differs, so rows differ; not valid).

### D7. Portfolio regions as an optional stage of the existing run resource
When a portfolio run request carries a `decision` block, the run adds a regions stage per scenario: each loan is classified, results are merged in loan order (the existing deterministic merge), and the snapshot gains aggregates: loan and exposure share per region, the expected-loss range (`CERTAIN_HIGH` exposure x loss at member minimum to all non-`CERTAIN_LOW` exposure x loss at member maximum), and boundary exposure by segment. Per-loan regions are exposed on the loan listing with a `region` filter so analysts can pull the boundary list. **Why attach to the run:** it inherits the 202/heartbeat/one-active-run behaviour, replica-safe polling and tenant isolation instead of inventing a second long-running resource. **Alternative:** a synchronous endpoint (simple, but blocks for large books and bypasses the run limits). If storing aggregates needs a schema change, the migration follows the existing Flyway-style conventions and ships RLS policies for any new tenant table.

### D8. Extrapolation flag
After evaluating the granule, the overlay collects `engine.extrapolations` for each member and keeps only the extrapolations a scenario can cause: `unemployment`, `unemployment_change_12m`, `hpi_change_12m`, `rate_incentive`, and `mtm_ltv` above its trained ceiling. Measured on the committed engine, every lifetime projection of a 30-year loan runs past the 96-month training window in `loan_age` and takes `mtm_ltv` below its floor through amortisation, in the baseline too, so flagging those would make the flag always true. If any member leaves the trained range on a kept driver, the loan's result carries `extrapolated = true` and the driver list. **Why:** LightGBM holds its response at the training edge, so members past the edge give nearly equal scores and the boundary shrinks for the wrong reason. The flag does not change the region; it qualifies it. **Alternative:** widen the boundary automatically (no evidence for any particular widening; would be a made-up correction).

### D9. Independent reference and golden contract
A Python script (`backend/golden_rough_set.py`) implements splitmix64 member generation, the clamping rules and the classifier independently of Java, and writes a fixture holding `(seed, target scenario, options) -> member scenarios` and `(metric vectors, threshold, beta) -> regions`. Java is held to it exactly for the generator and to 1e-9 for derived widths. The engine's own outputs are already held to Python by `TermStructureGoldenTest`, so this fixture does not re-test the model. **Why:** it gives the new layer the same independent-reference discipline the repo uses elsewhere, without a new model artifact.

### D10. API is additive and labelled
`POST /risk/term-structure` and `POST /risk/term-structure/compare` accept an optional `decision` object (`metric`, `threshold`, `tolerance`, `granule` with `kind`, `members`, `shockBand`, `timingBandMonths`, optional `scenarios` for `SCENARIO_SET`, optional `seed`). Responses add a `decision` block with the region, counts, spread, `extrapolated`, the seed used and a fixed `method` label: "rough-set three-way decision over a seeded perturbation granule; not a probability and not a coverage-guaranteed interval". Invalid options return 422 like other projection errors. Requests without `decision` are byte-identical in behaviour. **Alternative:** a new endpoint (cleaner separation, but duplicates validation, scenario parsing and metering). The existing `@Metered` annotation charges one unit per call and the audit trail records only the model version, so neither can express the granule size. A decision call is therefore metered as usual under `TERM_STRUCTURE` and additionally records `members` units of a new `DECISION_MEMBER` metric through a quantity overload of `UsageService.record`; the audit trail carries the model version like other calls, and the decision parameters are echoed in the response.

### D11. UI shows regions, never a probability
Lifetime Risk shows a region badge with the metric range against the threshold and a one-line method note. Portfolio Risk shows the three-region split, the expected-loss range and a boundary-loan list linking to each loan's page. Copy never calls the boundary a confidence level. The component is lazy-loaded if the initial-bundle budget requires.

### D12. Benchmark note in the model card, not a second method
`MODELS.md` records what the method guarantees and what it does not, and states how it differs from conformal prediction (which needs a calibration set and gives coverage). A small experiment under `backend/experiments/` measures boundary size per scenario on the committed artifacts, so the documentation reports a measured figure, not an assumed one.

## Risks / Trade-offs

- **The boundary depends on the chosen neighbourhood (`shockBand`, `timingBandMonths`)** → defaults are documented, echoed in every response, and the experiment reports boundary size as a function of the band so the sensitivity is visible. The band is a modelling choice, not a measured quantity, and the card says so.
- **Flat tree extrapolation narrows the boundary under severe stress** → the `extrapolated` flag and its driver list; the UI states that a narrow boundary under an extrapolated scenario should not be read as certainty.
- **Cost grows as `loans x n`** → bounded `n`, batched evaluation, portfolio default of 16, a measured budget task, and the existing sampling rule if a book is large enough to need it.
- **Rough sets can be mistaken for probabilities or confidence** → fixed method label in the API, UI and card; a response test asserts the label is present.
- **Parallel nondeterminism in the portfolio stage** → counter-based seeds, results merged in loan order, and a test comparing 1 thread with many.
- **Dependency on an unlanded change** → regime draws are optional and rejected explicitly when unavailable; nothing else depends on them.
- **Regression risk to validated models** → D1 keeps the overlay outside the engine; a test asserts the survival card checksum and golden output are unchanged by this change.

## Migration Plan

Additive. Ship in four steps: (1) the classifier, generator and Python golden; (2) single-loan API fields; (3) the portfolio stage and any schema migration; (4) the UI and documentation. Callers that send no `decision` see no change. Rollback is removing the optional fields' handling and the overlay package; no existing artifact or table depends on them. A portfolio schema change, if needed, is a forward-only migration whose new columns are nullable.

## Open Questions

1. Resolved: default `shockBand` ±20% and `timingBandMonths` ±3, kept after measuring (below). Measured with `BoundarySizeMeasurement` (32 variants, seed 17) on the committed engine, a 30-year prime loan (credit score 760, LTV 75) and a weak one (640, LTV 95, MI 25%): the half-width of the spread of the figure across variants, as a share of the unperturbed figure.

   | Figure and scenario | band ±10% / ±3 mo | ±20% / 0 | **±20% / ±3 (default)** | ±20% / ±6 | ±30% / ±3 |
   |---|---|---|---|---|---|
   | Prime, ADVERSE, lifetime PD | 3.7% | 7.6% | **7.1%** | 7.6% | 10.8% |
   | Prime, ADVERSE, 12-month PD | 18.9% | 16.2% | **24.6%** | 34.5% | 29.8% |
   | Prime, SEVERELY_ADVERSE, lifetime PD | 11.7% | 22.5% | **21.5%** | 22.8% | 31.8% |
   | Prime, SEVERELY_ADVERSE, 12-month PD | 33.9% | 26.3% | **42.5%** | 59.8% | 54.4% |
   | Weak, ADVERSE, lifetime PD | 1.7% | 3.3% | **3.4%** | 3.5% | 5.0% |
   | Weak, ADVERSE, 12-month PD | 10.9% | 8.2% | **12.6%** | 17.0% | 13.6% |
   | Weak, SEVERELY_ADVERSE, lifetime PD | 1.3% | 2.2% | **2.2%** | 2.6% | 3.4% |
   | Weak, SEVERELY_ADVERSE, 12-month PD | 5.9% | 5.9% | **8.3%** | 13.1% | 11.6% |

   Two readings. Timing barely moves a lifetime figure but moves a 12-month figure a lot (the shock lands inside or outside the first year), so the timing band matters most for `PD_12M`. And the same band gives a wider boundary the harsher the scenario and the lower the loan's own PD, so a fixed threshold sits on the boundary for a different share of loans in each scenario. The weak loan's lifetime PD (57%) is high enough that stress variants barely move it. The first run was stopped part-way because it was slower than estimated; the missing weak-loan rows were run afterwards, so all 72 rows (2 loans x 2 scenarios x 9 band pairs x 2 figures) now exist.
2. Resolved: portfolio aggregates are stored in the snapshot's `detail` JSON, and per-loan regions as nullable columns on `portfolio_risk_loan` (one forward-only migration; that table already carries RLS).
3. Whether `ECL_LIFETIME_RATE` should use `eclLifetime` or the IFRS 9 / CECL figure for its default (proposed: `eclLifetime`, with the others selectable later).
4. Whether the `SCENARIO_SET` granule should be offered on the portfolio path in this change or only on single loans.
