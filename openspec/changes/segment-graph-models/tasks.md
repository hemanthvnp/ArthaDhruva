Estimated effort for one engineer: about 9 working days (group 1: 2.5, group 2: 2.5, group 3: 1, group 4: 2.5, group 5: 0.5), plus 1.5 for the stretch task 4.7. The first hand-over is the end of group 2, about day 5. Nothing here is started.

## 1. Baseline and data

- [ ] 1.1 Reproduce the measured baseline: `backend/experiments/segment_baseline.py` recomputes the numbers in the design from `data/docs/segment_monthly_series.parquet` and prints them next to the documented values. Done when they match to the printed precision.
- [ ] 1.2 `backend/build_state_panel.py`: a chunked map-reduce over the monthly panel (`data/processed/monthly_panel/`) joined to the loan-level table on `loan_sequence_number`, following `pipeline/chunked.py` (bounded slices, one part file per slice, resumable). Output `data/processed/segments/state_month_cells.parquet`: per state, month, loan-age bucket, credit-score band and LTV band, the loans active, the loans 90 or more days past due or in REO, and of those the number flagged for disaster (`delinquency_due_to_disaster`) and for borrower assistance (`borrower_assistance_status_code`). Done when deleting one slice and re-running rebuilds only that slice, and the national active-loan count by month agrees with `data/docs/portfolio_monthly_series.parquet` (or the difference is explained in the script's header).
- [ ] 1.3 Derive the standardised series: expected counts with leave-one-state-out national rates per cell and month, `log(observed / expected)` for the primary definition and the two sensitivity variants, missing where expected is below 20, and a coverage table (share of months present per state). Done when every state below 90% coverage is named in the output.
- [ ] 1.4 `backend/build_macro_panel.py`: from `data/processed/survival/macro_state.parquet`, the monthly change in unemployment and quarterly house-price growth from quarter-end values; each state's series regressed on the national series (`macro.parquet`), residuals standardised; places whose series fell back to the national one (expected: the territories) listed and left out. Done when the first principal component of the residual panel is reported and is well below the 89.5% of the current series.
- [ ] 1.5 Checks and tests: the build stops on a missing state, a duplicate key or an empty series; pytest tests on synthetic data (a state with a known excess is recovered by the standardisation; leave-one-out really excludes the state); add `pytest` to `requirements-notebooks.txt` and a `python-tests` job to `.github/workflows/ci.yml`. Done when CI runs them.
- [ ] 1.6 Feasibility gate: record in the design which cell definition is used, the coverage found, and whether the disaster and forbearance variants change the series by more than noise. Stop and discuss if fewer than 40 states reach 90% coverage.

## 2. Learn the graph

- [ ] 2.1 `backend/export_segment_graph.py`, moves-with links: `GraphicalLassoCV` with `TimeSeriesSplit` on the residual panels (unemployment and house prices separately); partial correlations from the precision matrix.
- [ ] 2.2 Leads links: per-state lasso on lags of every state (6 months for unemployment, 2 quarters for house prices), penalty by rolling-origin validation; keep sign and lag.
- [ ] 2.3 Stability selection: 200 moving-block bootstrap resamples (12-month blocks, fixed seed); keep links selected in at least 80%; confidence is the selection frequency; union of the two sources with `basis`. Done when a synthetic chain A, B, C yields no A-C moves-with link.
- [ ] 2.4 Regions and bellwethers: Louvain with a fixed seed on the confident positive moves-with links, every region at least 3 states, stability from co-assignment across resamples; per-state counts of states led and followed.
- [ ] 2.5 Validation per design decision 5 in `backend/experiments/segment_graph_validation.py`: learn through 2016-12, test from 2017-01; linked versus unlinked with the degree-preserving null; the three comparisons; the direction test; the robustness runs. Results go into the card.
- [ ] 2.6 Artifact and card: `segment_graph.json` and `segment_graph_card.json` (using `credit_common.write_json` and `sha256`), validated against the schema in the design (`backend/risk-engine/src/test/resources/segment/segment_graph.schema.json`, kept by the serving side; until it exists, validate against the shape in the design). Done when two runs give identical checksums. This is the first hand-over.

## 3. Regional factors

- [ ] 3.1 Region-quarter panel: reproduce the validation fit of `backend/export_survival_model.py`, keep its per-loan-month hazards for 2023-01 onward, and aggregate actual and expected defaults by region and quarter.
- [ ] 3.2 Correlation between regions: Ledoit-Wolf shrinkage toward the identity; check unit diagonal and positive semi-definiteness.
- [ ] 3.3 Regional share: the moment relation in design decision 6, with a bootstrap interval over quarter blocks.
- [ ] 3.4 Recovery test: simulate portfolios from the two-level factor model with a known regional share at the available sample size; the known value is inside the interval in at least 90% of 200 simulations. A pytest test.
- [ ] 3.5 Write `regionCorrelation` and `regionalShare` into the graph file and card. If the interval includes zero, the default is zero and the card says so.

## 4. Regional outlook

- [ ] 4.1 Confirm the decision rule in design decision 7 before running any backtest. Amendments are dated and recorded there.
- [ ] 4.2 `backend/experiments/segment_outlook.py`: the target, and the features without and with the graph (linked states' recent values weighted by link strength, the region mean, leaders at their lags), the graph being the one learned through 2016-12.
- [ ] 4.3 Candidates: no change; own-history autoregression; ridge with state effects and LightGBM, each without and with graph features (seeded, deterministic).
- [ ] 4.4 Rolling-origin backtest with the Diebold-Mariano tests and rank correlations, for all months and excluding the forbearance period.
- [ ] 4.5 Intervals: split-conformal from rolling-origin residuals; report the coverage of the 80% interval.
- [ ] 4.6 Outcome: if a candidate passes the rule, `backend/export_segment_forecast.py` writes `segment_forecast.json` with its checksum recorded in the card; otherwise record the negative result in `MODELS.md` under "What was tried and rejected" and ship no forecast file.
- [ ] 4.7 Stretch: a graph neural network (one graph-convolution layer over the learned links and a recurrent layer over time) in `backend/experiments/segment_gnn.py`, under the same protocol. Keep it only if it passes the rule.

## 5. Documentation and hand-over

- [ ] 5.1 `MODELS.md`: a "Segment graph" section covering the method, validation results, what was tried and rejected, and the known limitations.
- [ ] 5.2 README: the commands in "Rebuilding the data and the models".
- [ ] 5.3 Reproducibility: run the exports twice and compare checksums.
- [ ] 5.4 Hand-over note in the final pull request: what changed between the bootstrap graph and the learned one (links added and removed, regions, coverage), which optional fields are present, and the figures the page should quote.
