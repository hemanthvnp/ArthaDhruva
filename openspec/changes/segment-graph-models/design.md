## Context

Today the Segment Graph is built from one file and served by three more.

| Part | File | What it does |
|---|---|---|
| Series | `data/docs/segment_monthly_series.parquet` | Monthly delinquency rate for the top 15 states by loan volume, January 2020 to September 2025. Produced by a chunked map-reduce that is not in the repository (the notebook only loads its output). |
| Graph | `backend/export_segment_correlation.py` | Pearson correlation of the rate levels; pairs at 0.95 or more become edges; writes `segment_correlation_graph.json` (15 nodes, 41 edges). |
| Refresh | `backend/batch_refresh_segment_correlation.py` | The same computation, written straight into a running Neo4j. |
| Serving | `graph/GraphLoader.java`, `SegmentGraphService.java` | Merge the JSON into Neo4j at start-up; answer hop-distance queries. |

The data that exists, and what each part can support:

| Data | Where | Coverage |
|---|---|---|
| Monthly loan performance | `data/processed/monthly_panel/` (partitioned by origination year and quarter) | About 474 million loan-months. Has `loan_age`, `current_loan_delinquency_status`, `current_actual_upb`, `delinquency_due_to_disaster`, `borrower_assistance_status_code`. **No property state.** |
| Loan attributes | `data/processed/loan_level/` | `property_state`, `credit_score`, `original_ltv`, joined to the panel on `loan_sequence_number`. |
| State macro | `data/processed/survival/macro_state.parquet` (built by `backend/fetch_macro.py`) | Unemployment (monthly) and FHFA house price index (quarterly, interpolated to months), 1995-01 to 2026-08. National series in `macro.parquet`. |
| Survival sample | `data/processed/survival/loans/`, `rows/` | 1,439,988 sampled loans (40,000 per origination quarter), about 44,000 defaults. |

### Measured on 2 October 2026

| Finding | Evidence |
|---|---|
| The graph mostly draws the national cycle | First principal component of the 15 standardised series: 89.5% of variance. Pairwise correlation of levels: median 0.928, minimum 0.367, maximum 0.991. 41 of 105 pairs are at 0.95 or more, 67 at 0.90 or more. |
| Even monthly changes are dominated by it | Pairwise correlation of monthly changes: median 0.899, minimum 0.536. |
| Real structure exists underneath | After regressing each state's monthly change on the cross-state average change, 37 of 105 pairs have a correlation beyond the 5% threshold (0.24 with 68 months). About 5 would by chance. The average includes the state itself, which biases these residual correlations slightly negative (median -0.067); the new method uses the national series instead. |
| Edges are not stable | Rank agreement of pair correlations between the first and second half of the sample: -0.23 for levels, 0.23 for the residuals. Edges at r >= 0.95: 56 in the first half, 67 in the second, 32 in both. |
| Thin cells | The smallest state-month has 13 active loans; the median has 218,523. |
| Coverage | 15 of 51 places. The demo portfolio has 400 loans in 47 states; 261 are in the 15. |

### What the sampled defaults can and cannot support

The survival model is trained on a sample. Counting its defaults by state and quarter:

- About 44,000 defaults over 37 quarters, and about 43% of them fall in calendar 2020.
- In a median quarter, 34 of 54 places have 5 or more sampled defaults and only 11 have 20 or more (California 91, Florida 70, Texas 76, New York 33, Illinois 33, Georgia 29; Vermont, South Dakota and Wyoming 1 or 2).
- The smallest samples are the territories: Virgin Islands 88 loans, Guam 165, Puerto Rico 236.

An actual-minus-expected measure per state and month built on this sample would be mostly Poisson noise. The full panel does not have this problem, which is why decision 2 builds the delinquency series from it.

## Goals / Non-Goals

**Goals:**
- A graph whose links are learned, directed where the data supports it, and tagged with a confidence, covering the 50 states and DC.
- Validation on data the graph was not learned from, against alternatives a reviewer would ask about, with the result published whatever it is.
- The numbers a loss simulation needs to add a regional factor, with honest uncertainty.
- A forecast of regional deterioration if, and only if, it beats simple baselines out of time.
- The same integrity story as every other model: versioned artifacts, model cards, checksums, reproducible exports.

**Non-Goals:**
- No serving, page or loss-engine code in this change. The files below are the interface.
- No online learning and no model served at request time.
- No territories: they have too few loans and no state-level macro series.
- No causal claims. "Leads" means that one state's past improves the prediction of another's future after everything else is accounted for. It does not mean one state's trouble causes the other's.
- No use in individual credit decisions. State is already a feature of the survival model and can stand in for protected characteristics; the graph is portfolio-level analysis only.

## Decisions

**1. Learn from macro data; validate on delinquency data.** The graph is learned from state unemployment changes and house-price growth, 1995 to 2026, with the national movement removed. It is validated on the delinquency series of 2017 onward, which is different data. *Why:* the delinquency data has nine years and one downturn, which was a pandemic with forbearance; a graph learned from it would mostly learn that episode. The macro series cover three decades including the housing crash, and they are the drivers the survival model already uses, so the graph describes how the causes of credit risk move together. Using different data to validate than to learn is what makes the test mean something. *Alternative considered:* learn from delinquency directly. It is kept as a comparison baseline (learn on 2017 to 2021, test on 2022 onward), not as the method.

**2. The series for validation and forecasting is a standardised delinquency ratio from the full panel.** For state *s* and month *t*, the ratio is the number of active loans 90 or more days past due (or in REO), divided by the number expected if the state's loans, in each cell of loan age x credit-score band x LTV band, were delinquent at the national rate for that cell and month. The national rate leaves the state out (leave-one-state-out), so California is not compared with a rate it largely determines. The modelled quantity is the log of the ratio, missing where the expected count is below 20. This is indirect standardisation, the standard way to compare rates across populations with different mixes. It needs one chunked pass over the panel and no fitted model. *Why:* the measurements above. The sampled defaults are too few for a state-level surprise against the survival model, and a surprise would also depend on a model that was fitted on the same data and absorbs part of the regional signal it is meant to measure. *Alternatives considered:* (a) survival-model surprise at state-month level, rejected for noise; (b) the raw delinquency rate, rejected because it mixes in the age profile of each state's book, which drifts with origination waves. The variants that exclude delinquencies flagged for disaster (`delinquency_due_to_disaster`) and for forbearance (`borrower_assistance_status_code`) are built too, for sensitivity: a hurricane is a regional event the macro data cannot see.

**3. Two kinds of link, estimated separately.** "Moves with" is a direct same-period association: a sparse partial correlation (graphical lasso, penalty chosen by time-series cross-validation), so that two states that are linked only through a third are not linked. "Leads" is predictive precedence: for each state, a lasso regression on lagged values of every state (up to 6 months for unemployment, 2 quarters for house prices), so that a link survives only if it adds to the target's own past and to everything else. *Why:* they answer different questions, and only the second supports the page's "has tended to follow". *Alternative considered:* one Pearson matrix with a lag scan. That is the current method with a lag; marginal correlations are dominated by the shared national factor and by indirect paths.

**4. Stability selection decides which links exist.** Both estimators are refitted on 200 moving-block bootstrap resamples (blocks of 12 months, fixed seed). A link is kept if it is selected in at least 80% of them, and that share is its `confidence`. *Why:* with 1,275 pairs and a few hundred months, a single fit picks up chance links, and lasso p-values are not valid. Stability selection bounds the expected number of false links and gives a confidence per link. Blocks keep the time structure that an ordinary bootstrap destroys. *Alternative considered:* Pearson correlations with a Benjamini-Hochberg correction. It ignores indirect paths and autocorrelation.

**5. The validation protocol is fixed before results are seen, and includes the baselines that matter.**
- Learn on data through December 2016. Test on the standardised delinquency series from January 2017.
- Statistic: the mean correlation of 3-month changes in the log ratio among linked pairs, minus the same among all other pairs. Null: degree-preserving random rewirings of the learned graph (at least 2,000), one-sided.
- Compared against: states in the same Census division, the existing 0.95 correlation graph, and a graph learned from the delinquency data (decision 1). The learned graph adds information beyond geography only if it beats the same-division grouping at the 5% level, and the card says so in either case.
- Direction: for each leads link A to B at lag k, whether the correlation of A at *t* with B at *t + k* exceeds the reverse. A binomial test of the share of links where it does, against 50%. If the share is not above chance, the leads links are left out of the file and the card says why.
- Every headline number is reported for all months and for the period excluding March 2020 to March 2021 (forbearance), and with and without the disaster-flagged delinquencies. A conclusion that changes between these is labelled as not robust.

**6. Regions come from the graph; the regional parameters are estimated against the survival model.** Regions are Louvain communities of the confident positive moves-with links (fixed seed), with no region of fewer than 3 states; stability is how often two states land together across the resamples. For the loss simulation, the engine feeds the survival model's PDs into a one-factor copula and a regional factor would extend it:

```
X_i = sqrt(rho_n) Z + sqrt(rho_r) R_region(i) + sqrt(1 - rho_n - rho_r) e_i
```

The regional factor has to describe variation beyond what those PDs already carry, because the survival model already uses state unemployment and house prices. So the estimate uses the survival model's out-of-time predictions (the validation fit, which is trained before 2023) aggregated by region and quarter, where counts are large enough (roughly 100 to 250 sampled defaults per region-quarter in 2023 to 2025, depending on the number of regions), not the state-month series of decision 2. To first order, the variance *v* of the regional deviation of the default rate on the probit scale, net of binomial sampling noise, equals `rho_r / (1 - rho_n - rho_r)`, so `rho_r` is about `v (1 - rho_n) / (1 + v)`. The correlation between regions is a Ledoit-Wolf shrinkage estimate toward the identity. Both come with a bootstrap interval over quarter blocks. If the interval for `rho_r` includes zero, the shipped default is zero. Before it is used on real data, the estimator has to recover a known `rho_r` on simulated portfolios. *Limits:* only three out-of-time years, all benign, so expect a small and uncertain estimate. That is stated in the card. *Alternative considered:* estimate from the state-month series of decision 2. That series includes regional variation the PDs already carry, so it would overstate the residual factor.

**7. The outlook has to beat declared baselines out of time, or nothing ships.** This rule is part of this design, and any change after results are seen is recorded as a dated amendment here, never silently edited.
- Target: the change in a state's log ratio between the mean of the last three months and the mean of the next three.
- Origins: monthly from January 2021, expanding window, 3-month horizon.
- Baselines: no change; an autoregression on the state's own history.
- Candidates: ridge with state effects, and LightGBM, each without and with graph features (linked states' recent values weighted by link strength, the region mean, leaders at their lags). Macro features and the national series are in both. No calendar-month features: the survival experiments showed they absorb the 2020 forbearance spike.
- Graph features use the graph learned through December 2016, at every origin, never the one learned on the full sample.
- Test: Diebold-Mariano on the cross-state mean squared error per origin, HAC variance with lag 2, Harvey-Leybourne-Newbold correction, one-sided at 5%. Also the average cross-sectional rank correlation between predicted and realised change.
- Rule: a candidate is forecastable only if it beats both baselines. The graph is used only if the graph version beats the same model class without graph features at 5% and has the higher rank correlation. The best passing candidate ships. If none passes, no forecast ships and the result goes in `MODELS.md` under "What was tried and rejected".
- Intervals: split-conformal, from rolling-origin residuals; backtest coverage of the 80% interval is reported.
- Reported for all months and excluding the forbearance period.

**8. Outputs are files with model cards and checksums.** Same convention as `pd_model_card.json`: version (`YYYY.MM.DD-HHMM`), training windows, algorithm and settings, validation, limitations, SHA-256 of the artifact. Exports use fixed seeds and deterministic settings; two runs produce identical files.

**9. Everything is computed offline and shipped as data.** Production has no feed of new loan performance, and every other model in the service is trained offline too.

## The files

All three are written to `backend/risk-engine/src/main/resources/`. Values are examples.

`segment_graph.json`, read by the serving side:

```json
{
  "schemaVersion": 1,
  "version": "2026.10.12-1410",
  "asOf": "2025-09",
  "method": "graphical lasso and sparse VAR, stability selection",
  "learnedOn": { "from": "1995-01", "to": "2026-08", "sources": ["unemployment", "hpi"] },
  "states": [
    { "code": "FL", "name": "Florida", "region": "R3", "covered": true, "leads": 2, "follows": 0 }
  ],
  "regions": [
    { "id": "R3", "label": "Southeast", "states": ["AL", "FL", "GA", "SC"], "stability": 0.88 }
  ],
  "movesWith": [
    { "a": "FL", "b": "GA", "weight": 0.31, "sign": 1, "confidence": 0.94, "basis": ["hpi"] }
  ],
  "leads": [
    { "from": "FL", "to": "GA", "lagMonths": 3, "weight": 0.22, "confidence": 0.86, "basis": ["hpi"] }
  ],
  "regionCorrelation": { "regions": ["R1", "R2", "R3"], "matrix": [[1, 0.2, 0.1], [0.2, 1, 0.3], [0.1, 0.3, 1]] },
  "regionalShare": { "estimate": 0.02, "interval": [0.0, 0.05], "default": 0.0 }
}
```

- `states` lists all 50 states and DC. `covered` is false for a state the models could not use.
- In `movesWith`, `a` sorts before `b`, so each pair appears once. `weight` is a partial correlation in [-1, 1]. In `leads`, `weight` is a standardised coefficient.
- `regions`, `leads`, `regionCorrelation` and `regionalShare` may be empty or absent. The serving side must handle each of them being absent.
- `confidence` and `basis` are on every link this change produces. The serving side's bootstrap file (see the migration plan) may leave them out.
- A region `label` is a short name chosen by hand from the member states, or "Region N" if none fits.

`segment_graph_card.json` has the keys of `pd_model_card.json` that apply: `model` (`segment_graph`), `version`, `trained_at`, `algorithm`, `hyperparameters`, `training_window`, `validation` (linked versus unlinked, each baseline, direction test, robustness runs, region stability), `limitations`, `artifact_sha256`. A card without a `validation` section marks the artifact as not validated, which is how the bootstrap file's card reads.

A JSON Schema for `segment_graph.json` lives with the serving side's tests (`backend/risk-engine/src/test/resources/segment/segment_graph.schema.json`). The export validates its output against it before writing, so a change to the shape has to be agreed by both sides and made in the schema, the fixtures and this document together.

`segment_forecast.json`, only if a forecast ships:

```json
{
  "schemaVersion": 1,
  "version": "2026.10.20-0900",
  "asOf": "2025-09",
  "horizonMonths": 3,
  "model": "ridge with graph features",
  "decision": "beat no-change and autoregression at 5%; graph beat no-graph at 5%",
  "forecasts": [ { "state": "FL", "expectedChange": 0.12, "interval": [-0.05, 0.31] } ],
  "backtest": {
    "origins": { "from": "2021-01", "to": "2025-06" },
    "candidates": [ { "name": "no change", "rmse": 0.081, "rankIC": 0.0 } ],
    "intervalCoverage80": 0.78
  }
}
```

`expectedChange` is in log points of the standardised ratio: 0.12 means about 13% more delinquent, relative to the same-mix national rate, than over the last three months.

## Risks / Trade-offs

- **[Risk]** Macro links may not carry over to delinquency. → Decision 5 tests exactly this, and the card says so if the learned graph does not beat geography. The graph is then described as macro co-movement.
- **[Risk]** No lead-lag link survives stability selection or the direction test. → The graph ships without `leads`; the serving side drops what depends on them.
- **[Risk]** The regional share is indistinguishable from zero on three benign years. → Default zero, shown as a what-if setting with its interval. That is a finding about the data, not a failure.
- **[Risk]** The full-panel pass is long and memory-hungry; `pipeline/chunked.py` documents what happened to full-file group-bys on a 16 GB machine. → Bounded slices, one part file each, resumable. Fallback if it cannot be done: validate on the existing 15-state series and defer the forecaster.
- **[Risk]** Forbearance in 2020 dominates any delinquency series. → Every headline number has a run excluding it, and the card flags conclusions that depend on it.
- **[Risk]** Hurricanes and wildfires are regional shocks that macro data cannot see. → The disaster-flagged variants are built, and the limitation is in the card.
- **[Risk]** About 100 months by 51 states gives a forecaster little power. → The pre-registered rule and an accepted negative result, instead of tuning until something wins.
- **[Risk]** The numerical code has no tests today. → pytest on synthetic data with known answers, in CI.
- **[Trade-off]** Standardisation by indirect cells needs one more pass over 474 million rows. It is the price of a series with enough counts in every state.

## Migration Plan

1. The serving side starts from a bootstrap file in the same shape, made from the existing graph (15 states, "moves with" only, no regions, `validated` false in its card). Nothing waits on this change.
2. This change delivers in stages, each a pull request that replaces files: the panels (no runtime effect); the graph and its card (first hand-over, after group 2 of the tasks); the regional parameters (added to the graph file); the forecast (a new file).
3. Rollback is restoring the previous files. They are versioned, and the application checks their checksums at start-up.

## Open Questions

- **Cell definition for the expected counts.** Loan age only, or age x credit-score band x LTV band. Decided at the feasibility gate (task 1.6) by whether the extra cells change the series by more than noise.
- **Number of regions.** Left to the data within the 3-state minimum. Revisit if the regional estimates are unstable.
- **Is the graph neural network worth the time?** Decide after the ablation in group 4. With about 51 states and 100 months it is unlikely to beat the regularised models.
- **Three out-of-time years for the regional share.** If the interval is too wide to be useful, consider estimating on 2017 to 2022 using cross-fitted predictions, and record the leakage that introduces.

## Appendix: reproducing the baseline numbers

From the 15 x 69 table of state delinquency rates: the first principal component of the standardised levels; Pearson correlations of the levels over the full sample and over each half; correlations of monthly changes after regressing each state's change on the cross-state average change. Task 1.1 turns this into a script, and the numbers in the table above are what it has to reproduce.
