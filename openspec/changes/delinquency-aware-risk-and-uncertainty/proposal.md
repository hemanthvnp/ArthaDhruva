## Why

`MODELS.md` names two weaknesses that the current models cannot fix:

1. **The survival model does not know a loan is already late.** It "conditions on origination attributes and macro drivers, not on current delinquency status, so it understates the risk of a loan that is already past due." A 60-days-past-due loan and a current loan with the same attributes get the same projection. For a lender, the loans that are already late are the ones that matter most.
2. **Every lifetime figure is a point estimate.** The regime chain "is estimated from 99 months", the stressed-spell length and long-run stressed share "are uncertain, and the baseline lifetime PD inherits that uncertainty", yet the API returns one number. The portfolio engine already reports sampling error and CVaR already reports simulation intervals, but nothing reports uncertainty in the model itself.

Closing both turns the platform from "a model that scores loans at origination" into one that can answer the daily question: given where this loan is now, how likely is it to cure, roll or default, and how sure are we?

## What Changes

- Add a **delinquency-aware multi-state model**: a loan-month is in one of the states current, 30 days past due, 60 days past due, default or prepaid. A multinomial transition model, conditioned on the current state, payment history, loan attributes and macro drivers, gives the next month's state. It is an **additive** model with its own artifact, card and checksum; the existing survival model and its golden contract are not modified.
- Add an **exact multi-state term-structure engine** in Java. The projection carries the joint probability of (macro regime, delinquency state) forward month by month, so it stays an exact expectation with no simulation noise, and it reduces to the current engine's behaviour when the loan is current and history is absent.
- Add **uncertainty intervals** for lifetime PD and expected loss, combining (a) posterior draws of the regime chain, run exactly through the engine from fixed seeds, and (b) an empirical calibration error band measured on the vintage backtests. The result is labelled as what it is: a documented approximation of model uncertainty, not a full Bayesian posterior.
- Extend the **API** (additive and backward compatible) so term-structure, expected-loss and portfolio requests may carry a loan's delinquency status and history, and responses carry intervals and the model used.
- Show **ranges and the delinquency-conditioned projection** on the Lifetime Risk and Portfolio Risk pages.
- Register the new model in the **model inventory and card system**, with out-of-time validation, calibration by state, backtest and limitations, honouring the checksum rule (the service refuses to start on a mismatch).
- No **BREAKING** changes: requests without delinquency data behave exactly as today.

## Capabilities

### New Capabilities
- `delinquency-state-model`: the multi-state transition model, its features, training protocol, validation gates, artifact, card and reproducibility contract.
- `multi-state-term-structure`: the exact (regime x delinquency-state) projection engine, its invariants, determinism, fallback and golden contract.
- `lifetime-risk-uncertainty`: regime-chain posterior draws, the empirical calibration band, interval semantics and reproducibility.
- `delinquency-aware-risk-api`: additive request and response fields, validation, fallback rules and portfolio integration.
- `risk-uncertainty-display`: how the console presents delinquency-conditioned results and intervals without overstating precision.

### Modified Capabilities
<!-- None. openspec/specs/ is empty; related open changes (model-risk-governance) are referenced, not modified. -->

## Impact

- **Python:** new dataset columns and a new export script under `backend/` (additive: a separate dataset output and artifact; `build_survival_dataset.py` and `export_survival_model.py` outputs and checksums unchanged). New golden reference script for the multi-state engine.
- **Artifacts:** a new ONNX or JSON model, card, calibration and golden fixtures under `backend/risk-engine/src/main/resources/`.
- **Java:** new package for the multi-state engine and interval service; request/response additions in the survival and portfolio controllers; loader and checksum verification for the new artifact.
- **Frontend:** Lifetime Risk and Portfolio Risk pages; an interval component; types and tests; the initial-bundle budget must still pass.
- **Tests and CI:** new golden, brute-force, conservation, monotonicity and interval-reproducibility tests; no existing test, budget or model-validation check is weakened.
- **Data:** needs the Freddie Mac panel (delinquency status per month) that the existing pipeline already reads; rebuilding requires the raw archives, which are not in the repo.
- **Dependencies on other changes:** `model-risk-governance` (cards, inventory, version registry) is the home for the model's governance entry; this change must use it if it has landed, and otherwise follow the existing card format.
- **Out of scope:** housing-downturn vintages (2007 to 2010), disparate-impact analysis, a scorecard benchmark, non-fixed-rate products, collections and loss-mitigation workflows, and changing the origination PD model.
