## 1. Discovery and gates (gate: facts verified, tolerances frozen)

- [x] 1.1 Re-read `build_survival_dataset.py`, `export_survival_model.py`, `credit_common.py`, `TermStructureEngine`, `PortfolioRiskEngine` and the survival/portfolio controllers; list every place a delinquency field would enter
- [x] 1.2 Confirm the panel's `current_loan_delinquency_status` coding (numeric buckets, `RA`, missing) and how each value maps to the five states
- [x] 1.3 Count loan-months and events per starting state and per transition in the available data; record the counts that drive the D60 pooling decision (design open question 2)
- [x] 1.4 Check whether `model-risk-governance` has landed; if so, follow its card and registry conventions, otherwise follow the existing card format
- [x] 1.5 Confirm the raw archives needed for a rebuild are present; if not, stop and tell the owner before any pipeline step
- [x] 1.6 Freeze the baseline in `baseline.md`: survival artifact sha256, the golden test result, and the regime chain it embeds, taken after the regime-chain refresh and before any code in this change lands

## 2. Dataset and features (Python; additive, owner-run)

- [ ] 2.1 Add a separate dataset output with state, history features and next-state labels, leaving `build_survival_dataset.py` outputs unchanged
- [ ] 2.2 Implement the four history features strictly from prior months
- [ ] 2.3 Leakage test: altering months after t leaves every feature for month t unchanged
- [ ] 2.4 Apply the existing hygiene rules (drop the last month of each release, hash-of-loan-id split, 10% no-event subsampling with weight 10 where applicable)
- [ ] 2.5 Dataset checks: states are mutually exclusive, default definition matches the existing dataset, no loan straddles the split
- [ ] 2.6 Confirm the existing survival artifact checksum equals the `baseline.md` value after the new dataset code lands

## 3. Model training and export (owner-run only)

- [ ] 3.1 Pilot fit with fixed protocol (fit before 2023-01, evaluate after); record log loss and calibration by starting state
- [ ] 3.2 Freeze numeric tolerances for the validation gates in the spec from the pilot (design D7), with the reasoning recorded
- [ ] 3.3 Roll-rate transition table benchmark (state by loan-age bucket) on the same out-of-time months
- [ ] 3.4 Production fit with deterministic LightGBM settings; export artifact with fixed ONNX graph name and float-exact thresholds
- [ ] 3.5 Gate checks in the export that refuse to write the artifact on failure and name the failed gate
- [ ] 3.6 Monotonicity grid test (current, 30, 60 days past due) over a grid of loans
- [ ] 3.7 Model card: protocol, out-of-time metrics, calibration by state with event counts, benchmarks, limitations, checksum
- [ ] 3.8 Re-run the export twice and confirm the checksum is identical

## 4. Multi-state engine (Java)

- [ ] 4.1 New engine class carrying `a_k(r, s)` through regime transition then state transition; `TermStructureEngine` untouched
- [ ] 4.2 Reuse exposure, LGD with collateral floor, discounting and scenario paths from the existing engine rather than copying them
- [ ] 4.3 Staging from supplied delinquency state, with the existing rules as the fallback
- [ ] 4.4 Fallback routing: no delinquency data, or current with no history, uses the existing engine; assert bit-identical results
- [ ] 4.5 Out-of-range driver reporting for the new model's training ranges
- [ ] 4.6 Artifact loader with checksum verification and start-up refusal on mismatch
- [ ] 4.7 Portfolio integration: per-loan routing, per-model counts, bit-identical totals across thread counts

## 5. Engine verification (gate: all pass before any API work)

- [ ] 5.1 Python reference implementation and fixtures for the multi-state engine
- [ ] 5.2 Golden test: Java matches the reference to 1e-6
- [ ] 5.3 Brute-force test: enumerate regime paths for a short horizon and apply the state recursion within each path; agree to 1e-12
- [ ] 5.4 Conservation test: active plus defaulted plus prepaid equals 1 every month
- [ ] 5.5 Monotonicity test across starting states
- [ ] 5.6 Equivalence test: with delinquent transitions disabled the engine equals the existing engine within 1e-9
- [ ] 5.7 Regression test: the existing survival model's checksum and golden output equal the `baseline.md` values
- [ ] 5.8 Thread-independence test on a portfolio

## 6. Uncertainty

- [ ] 6.1 Dirichlet posterior over the regime transition counts, with seeds derived from the request and draw index
- [ ] 6.2 Run each draw through the exact engine; summarise lower, point and upper values for lifetime PD and expected loss
- [ ] 6.3 Derive the calibration band from the vintage-backtest ratios per outcome and horizon; record the vintage count
- [ ] 6.4 Combine draws and band; clamp to valid ranges; assert the interval contains the point estimate
- [ ] 6.5 Compute backtest coverage by vintage and add it, with the small-sample caveat, to the card
- [ ] 6.6 Draw cap and time budget with the explicit "point estimate only" fallback
- [ ] 6.7 Reproducibility tests: same request gives identical bounds across repeats, replicas and thread counts
- [ ] 6.8 Optional stretch: bootstrap ensemble, only with measured cost and coverage gain, off by default

## 7. API

- [ ] 7.1 Optional delinquency fields on term-structure, expected-loss and portfolio requests, with validation and clear messages
- [ ] 7.2 Explicit fallback with the reason in the response
- [ ] 7.3 Additive response fields: `modelUsed`, interval object with method label, draws and vintages, out-of-range months
- [ ] 7.4 Portfolio uploads: per-loan delinquency where provided, per-model counts in the result
- [ ] 7.5 Audit entries record the model identifier and version; metering and rate limits unchanged
- [ ] 7.6 Inventory and card endpoints list the new model
- [ ] 7.7 Backward-compatibility test: an old request and old response shape still work
- [ ] 7.8 Integration tests as the non-owner role (tenant isolation unchanged)

## 8. Frontend

- [ ] 8.1 Types and API client for the new fields
- [ ] 8.2 Interval component with range, method note, and accessible text alternative
- [ ] 8.3 Delinquency inputs on the Lifetime Risk form with validation matching the API
- [ ] 8.4 Starting-state comparison view
- [ ] 8.5 Fallback message and the portfolio model split
- [ ] 8.6 Unit tests; run `npm run lint`, `npm test`, `npm run build`, `npm run check:bundle` in CI order; do not raise the budget

## 9. Documentation and governance

- [ ] 9.1 Update `MODELS.md`: inventory row, the new model section, the multi-state projection, interval method and its limits
- [ ] 9.2 Remove or amend the "does not condition on delinquency" limitation only for the cases the new model covers, keeping the remaining caveats
- [ ] 9.3 Register the model per `model-risk-governance` if it has landed
- [ ] 9.4 README feature table: lifetime risk now conditions on delinquency and reports ranges

## 10. Final verification

- [ ] 10.1 Run `./mvnw test` (Docker needed for integration tests) and the frontend CI sequence; report each command and its result exactly, and report any skipped check as skipped
- [ ] 10.2 Confirm no test, budget, security control or model-validation check was weakened
- [ ] 10.3 Confirm the owner ran the dataset build, export and golden reference scripts and the artifact checksum matches its card
- [ ] 10.4 Commits only when the owner asks, as `hemanthvnp` with no co-author line; never push to `prod` without explicit approval
