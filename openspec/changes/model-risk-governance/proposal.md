## Status

Implemented, with a different design from the one first proposed. `design.md` records what was built
and why each departure was made; `tasks.md` lists what is done and what was deliberately left out.

## Why

ArthaDhruva's models were exported once and swapped in place (`export_model.py` overwrote a fixed-filename `model.onnx`), with no record of which model version produced a given score and no check on whether a deployed model still fits the loans it is scoring. That is a workable shortcut for a demo, but the product's clients are lenders, and a bank cannot adopt a vendor's credit model without being able to answer "which model scored this loan", "how was it validated" and "is it still seeing the population it was trained on". The `saas-platform-expansion` proposal named this class of work (SR 11-7 model risk management, among others) and deferred it. This change covers the part of it that software can provide: a model inventory, score-to-version traceability, and validation and drift monitoring. The surrounding governance process (independent validation, sign-off, compliance as a legal matter) is not something code can satisfy and stays out of scope.

## What Changes

- **A model inventory that cannot disagree with what is running.** Each validated model is exported together with a model card: version, training window, algorithm and hyperparameters, in-time and out-of-time validation, known limitations, and the SHA-256 of the artifact. At startup the service hashes the artifact it actually loaded and refuses to start if it does not match its card. `GET /v1/models` lists every model the service runs, including the ones that are not validated yet, each with a plain statement of what is missing.
- **Every result names the model that produced it.** Persisted scores, audit events and stored portfolio results carry a `model_version`, and the scoring and expected-loss responses return it.
- **Validation against realized outcomes, shipped with the model.** The export scripts evaluate each model on vintages it never saw and write the results into the card; the survival model also ships a vintage-by-vintage backtest of predicted against realized cumulative default and prepayment.
- **Drift monitoring at run time.** `GET /v1/models/pd_24m/drift` compares the portfolio being scored with the training population, input by input and for the score itself, using the population stability index with a small-sample noise floor.
- **Model identity as a metric.** `model_info{model, version, sha256}` is exported per replica, and an alert fires when replicas serve different artifacts of one model.
- **BREAKING**: none. New columns are nullable or belong to new tables; response fields were added, none removed.

## Capabilities

### New Capabilities
- `model-version-registry`: the inventory of models, their cards, and the startup integrity check.
- `score-model-traceability`: every persisted score, audit event and portfolio result references the model version that produced it.
- `model-backtesting-monitoring`: out-of-time validation and vintage backtests exported with each validated model, and population-stability monitoring of the live portfolio.

### Modified Capabilities
- None. `openspec/specs/` is empty: no prior capability specs exist for this repository.

## Impact

- **Backend**: `governance` package (`ModelRegistry`, `DriftService`, `ModelGovernanceController`); migration V26 adds `model_version` to `loan_score` and `model_invocation_events`, and the portfolio-risk tables are created with it; `export_model.py` and `export_survival_model.py` write the cards, the drift reference and the backtest next to the ONNX files.
- **Frontend**: a Model Governance page (inventory, cards, validation, backtest charts, drift), and the model version on scoring results.
- **Infrastructure**: no new services. One info metric and one alert rule on the existing Prometheus setup.
- **Out of scope**: the regulatory and audit process around the models (formal sign-off, independent validation, SR 11-7 compliance as a legal matter, SOC 2).
