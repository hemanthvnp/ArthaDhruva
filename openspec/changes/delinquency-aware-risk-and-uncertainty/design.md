## Context

Established from `MODELS.md`, the README and the code (re-verify before building):

- **Data:** Freddie Mac loan-level sample, 40,000 loans per origination quarter, 2017 to 2026, each followed monthly. The panel already carries `current_loan_delinquency_status` (read in `build_survival_dataset.py`). Today default is the first month at 90+ days past due, an REO acquisition, or an adverse zero-balance termination; prepayment is a clean payoff; censoring is active-at-end or non-credit removal.
- **Existing survival model:** discrete-time multinomial hazard (default, prepay, continue), LightGBM, 23 features, trained with a hash-of-loan-id split, 10% subsampling of no-event months with weight 10, the last month of each release excluded, an out-of-time protocol (fit before 2023-01), a drift-overlay rule, and a vintage backtest against an Aalen-Johansen estimate.
- **Engine:** `TermStructureEngine` carries `a_k(r)`, the joint probability of "active at the end of month k and in regime r", through a two-state regime chain: an exact expectation over all 2^T regime paths in O(T). Loss uses exposure, a fitted LGD floored by a collateral shortfall, and discounting at the note rate. IFRS 9 staging uses 90+ and 30+ days past due and a significant-increase test.
- **Contract:** `TermStructureGoldenTest` holds Java to an independent Python implementation to 1e-6; exports are bit-for-bit reproducible (deterministic LightGBM, float-exact thresholds, fixed ONNX graph name); the service refuses to start on a checksum mismatch; the repo rule is that these checks are never weakened.
- **Existing uncertainty reporting:** the portfolio engine reports a standard error of lifetime ECL when it samples above 5,000 loans; CVaR reports intervals from 20 independent simulation sections. Neither reflects model (parameter or regime) uncertainty.
- **Neighbouring change:** `model-risk-governance` defines cards, the inventory and score-to-version traceability.
- **Repo rules binding this work:** export and golden scripts are run only when the owner asks; commits are by the owner only with no co-author line; secrets and `historical_data_*` stay untouched; report only checks that ran.

## Goals / Non-Goals

**Goals:**
- A projection that conditions on a loan's current delinquency state and recent history, exact in the same sense as today's engine.
- Intervals on lifetime PD and expected loss that are reproducible and honestly labelled.
- Zero behaviour change for callers that do not send delinquency data, and zero change to the existing model's artifact, checksum or golden test.
- Validation gates stated before training, judged out of time, in the same style as the existing model card.

**Non-Goals:**
- Housing-downturn data (2007 to 2010), disparate-impact testing, a scorecard benchmark, new products, collections workflows, or altering the origination PD model.
- A full Bayesian treatment of every parameter. The interval method is a documented approximation (see D5 and D6).
- Real-time servicing feeds: status is supplied by the caller per request.

## Decisions

### D1. A multi-state transition model, not extra features on the existing hazard
State space per loan-month: `C` (current), `D30`, `D60`, absorbing `DEF` and `PRE`. The model estimates `P(next state | current state, history, attributes, macro)` as a multinomial per source state. **Why:** adding "current delinquency" as a feature to the existing competing-risks hazard would fit, but cannot represent how a late loan *moves* (cure, roll, default) over the next months, which is what a lifetime projection needs, and it would force a refit of the validated model. **Alternative:** the feature-only variant (cheapest, but a late loan's projection would still ignore its path back to current); a pure roll-rate table (transparent, but no loan or macro conditioning). The roll-rate table is kept as the benchmark in D8.

### D2. Additive artifact: the existing model, checksum and golden stay untouched

"Untouched" is relative to the baseline frozen in `baseline.md`, taken after the 2025-09 regime-chain refresh (the regime chain was refitted without the panel's post-coverage-cliff months, which moved `survival_model.json` before this change started). This change must not move it again.

The new model has its own dataset output, export script, artifact, card and checksum. `build_survival_dataset.py` and `export_survival_model.py` outputs are unchanged, so the existing golden test and card keep passing without a re-export. **Alternative:** extend the existing dataset and model (one pipeline, but changes a validated Tier 1 model and invalidates its checksum for a feature most callers will not use).

### D3. History features, kept few and defensible
Current state, months since the last cure, count of 30+ episodes in the previous 12 months, and the maximum state reached in that window, plus the existing origination and macro drivers. **Why:** these are what the literature and servicing practice use, and they are available from the same panel. **Constraint:** no feature may use information from after the month being predicted; a leakage test enforces it (see specs).

### D4. Exact projection over (regime x delinquency state)
Carry `a_k(r, s)` forward: transition regime, then apply the state transition matrix for the month (conditioned on `r`, loan age and the loan's scheduled attributes), accumulate default and prepayment mass, and keep active mass by state. Cost O(T x R x S^2) with R = 2 and S = 3 active states, still deterministic and noise-free. A separate engine class is added; `TermStructureEngine` is not edited. **Fallback:** a loan with no delinquency data, or a current loan with no history, is routed to the existing engine, preserving today's results bit for bit. **Alternative:** simulate paths (simpler to write, but loses exactness, reproducibility and the brute-force verification the repo relies on).

### D5. Uncertainty part 1: regime-chain posterior draws, run exactly
Draw regime transition matrices from a Dirichlet posterior over the observed transition counts (the chain is estimated from 99 months), seeded per draw index so results are identical on any thread count. Each draw runs through the exact engine, so each draw is an exact expectation; the spread across draws is the regime uncertainty. **Why:** the regime chain is the documented dominant uncertainty and the engine is cheap enough for a few hundred exact runs per loan. **Limit:** only the regime chain is resampled; the hazard model's own parameter uncertainty is not, which is stated in the card and the response.

### D6. Uncertainty part 2: empirical calibration band from backtests
The vintage backtest already measures predicted over realized cumulative incidence by vintage. The dispersion of that ratio (per outcome and horizon, with the small number of vintages stated) defines a multiplicative band applied around the regime-draw interval. **Why:** it captures real error the draws cannot (model misspecification, drift), using evidence already produced. **Alternatives:** a bootstrap ensemble of refit models (fuller, but multiplies training cost and artifact size; kept as an optional stretch task with measured cost), conformal prediction (needs a calibration set per segment the data does not support at this granularity). The combined interval is labelled "regime uncertainty plus historical calibration error", never "confidence interval" in the statistical sense.

### D7. Validation gates fixed before training
Same protocol shape as the existing model: fit before 2023-01, evaluate out of time, hash-of-loan-id split. Gates (to be tightened or relaxed only by the owner, with the reason recorded): (1) among delinquent loan-months, out-of-time log loss beats the existing survival model; (2) predicted over realized 12-month default and cure rates within a stated tolerance for each starting state; (3) probability conservation and monotonicity (a worse starting state never has lower lifetime PD, all else equal) hold on a grid of loans; (4) interval coverage on the vintage backtests is reported with its small-sample caveat. Numeric tolerances are set in the first implementation task from a pilot fit, then frozen in the spec before the production fit.

### D8. Benchmarks included in the card
The existing survival model (blind to status) and a roll-rate transition table (state, loan-age bucket) are reported alongside the new model on the same out-of-time loan-months, so the card shows what the extra modelling buys.

### D9. Reproducibility and checksum follow the existing rules
Deterministic LightGBM, sorted rows, float-exact thresholds, fixed ONNX graph name, card with checksum, service refuses to start on a mismatch, a new Python golden reference for the multi-state engine, and the Java engine held to it at 1e-6. Interval draws use fixed seeds derived from the request, so the same request gives the same interval.

### D10. API is additive; the response says which model ran
Optional request fields carry delinquency status and history; responses add `modelUsed`, the interval fields and the interval method label. Missing or invalid delinquency data falls back to the existing engine and says so, instead of failing or silently guessing. **Alternative:** a new endpoint (cleaner separation, but duplicates validation, scenarios and metering).

### D11. UI shows ranges, never false precision
Figures with intervals render as a point estimate with a visible range and a short method note; when the fallback engine ran, the page says the status was not used. The initial-bundle budget is respected (lazy-load the interval component if needed).

## Risks / Trade-offs

- **Sparse deep-delinquency data (D60 and beyond) makes transitions noisy** → report calibration by state with counts, pool or smooth only with a documented rule, and state the limitation in the card.
- **Leakage through history features** → features computed strictly from months before the target, with an automated leakage test.
- **Cure behaviour reflects the 2017 to 2026 period (forbearance, low rates)** and may not carry to a downturn → stated limitation, reinforced by the existing "no housing downturn" caveat.
- **Intervals can look more rigorous than they are** → method label in every response, card and UI; the stretch bootstrap is the path to fuller coverage.
- **Engine complexity grows** → a separate class with its own brute-force check (enumerate regime paths for a short horizon, apply the state recursion within each path) so exactness stays verifiable.
- **Retraining cost and data availability** (raw archives are not in the repo) → the dataset build and export are explicit owner-run tasks, never run implicitly.
- **Regression risk to the validated model** → D2 keeps it untouched; a test asserts its card checksum and golden output are unchanged by this change.

## Migration Plan

Additive. Ship the artifact and engine behind the request fields: callers that send no delinquency data see no change. Roll out by (1) landing the Python pipeline and artifact, (2) landing the engine with tests, (3) the API fields, (4) the UI. Rollback is removing the optional fields' handling and the artifact; nothing existing depends on them. No database migration is expected unless the version registry from `model-risk-governance` requires a row for the new model, in which case that follows its own conventions.

## Open Questions

1. Exact numeric tolerances for the gates in D7 (set from a pilot fit, then frozen).
2. Whether to pool D60 with 90+ near-default months, depending on counts.
3. Whether the bootstrap-ensemble stretch is worth its cost after measuring D5 and D6 coverage.
4. Where the new model sits in the inventory tiers (proposed Tier 1, validated) and how the registry from `model-risk-governance` records it if that change has landed.
5. Whether portfolio runs should accept per-loan delinquency from uploaded files now, or only the single-loan endpoints first.
