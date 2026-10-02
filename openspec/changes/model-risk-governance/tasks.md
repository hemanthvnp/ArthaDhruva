The tasks as built. The original plan (a `model_version` table, a foreign key per score, a scheduled
backtest job) was replaced; `design.md` explains each departure.

## 1. Model cards (pipeline)

- [x] 1.1 `export_model.py` writes `pd_model_card.json`: version, training window, algorithm, hyperparameters, monotone constraints, in-time and out-of-time validation, limitations, SHA-256 of `model.onnx`
- [x] 1.2 `export_survival_model.py` writes the survival model's card (`survival_model.json`) with the same contents and the SHA-256 of `survival_model.onnx`
- [x] 1.3 `export_model.py` writes `drift_reference.json`: the training population's distribution per input and for the score
- [x] 1.4 `export_survival_model.py` writes `survival_backtest.json`: predicted against realized cumulative default and prepayment by vintage

## 2. Model registry (backend)

- [x] 2.1 `ModelRegistry` builds the inventory at startup: id, name, purpose, tier, validated or not, version, artifact, SHA-256, headline validation figures, gaps
- [x] 2.2 The loaded artifact is hashed and compared with its card; a mismatch stops the application
- [x] 2.3 Models without a card are listed as unvalidated, with their checksum and what is missing
- [x] 2.4 `GET /v1/models`, `GET /v1/models/{id}` (the full card), `GET /v1/models/survival/backtest`

## 3. Traceability

- [x] 3.1 Migration V26: nullable `model_version` on `loan_score` and `model_invocation_events`; the portfolio-risk tables created with it
- [x] 3.2 `LoanScoreService` stamps the version on every persisted score
- [x] 3.3 The audit trail records the version behind each model invocation
- [x] 3.4 `ScoreResponse` and `ExpectedLossResponse` return the version
- [x] 3.5 Portfolio runs store the version with their results

## 4. Monitoring

- [x] 4.1 `DriftService`: population stability index per input and for the score, against the training reference, with the small-sample noise floor
- [x] 4.2 `GET /v1/models/pd_24m/drift`
- [x] 4.3 `model_info{model, version, sha256}` metric per model per replica
- [x] 4.4 Alert rule `ReplicasServeDifferentModels`, with a unit test (`observability/alerts.test.yml`)

## 5. Frontend

- [x] 5.1 Model Governance page: inventory, cards, validation tables, backtest charts, drift
- [x] 5.2 Model version shown on scoring results

## 6. Tests

- [x] 6.1 `DriftServiceTest`: a sample of the training population reads as stable, a shifted portfolio is flagged on the inputs that shifted, PSI matches its formula, unknown categories are handled
- [x] 6.2 `ApiIntegrationTest`: the inventory, a card, an unknown model (404), the backtest, the drift report
- [x] 6.3 `ApiIntegrationTest`: the scoring response, its audit row and the exported scores carry the model version; `PortfolioRiskEngineTest`: a portfolio result carries the survival model's

## 7. Documentation

- [x] 7.1 `MODELS.md` at the repository root: every model, its validation, what was tried and rejected, known limitations
- [x] 7.2 This change's proposal, design and specs describe what was built

## Not done

- [ ] A scheduled comparison of stored scores with realized outcomes. It needs a servicing feed this deployment does not have (design decision 4).
- [ ] Model cards and out-of-time validation for the LGD, regime, early-warning and trajectory models. Each is listed as unvalidated in the inventory with its gaps.
- [ ] Drift monitoring for the survival model's macro inputs.
