## Context

Before this change the export scripts trained a model, printed holdout metrics to a terminal and wrote a fixed-filename ONNX file into the service's resources. The service loaded that file from the classpath with no identity beyond its filename; a persisted score stored the number but not which model produced it; nothing checked a deployed model against the loans it was scoring.

The first version of this design put model identity in Postgres: a `model_version` table written by the export scripts, a foreign key from each score, a warning in the log when the loaded file did not match the active row, and a scheduled job recomputing AUC from operational scores once their outcomes were known. That is not what was built. The sections below describe the design as implemented and, for each departure, why.

## Goals / Non-Goals

**Goals:**
- Every model the service runs has a durable identity tied to the exact bytes in use.
- Every persisted result can be traced to the model version that produced it.
- A model's validation against realized outcomes is recorded, versioned with the model, and readable through the API.
- A shift in the population being scored is visible while it is happening.
- Additive: no endpoint contract breaks, no table loses a column.

**Non-Goals:**
- No governance workflow (approval gates, sign-off, independent validation by a separate team).
- No automated retraining, promotion or rollback. A person runs the export and ships the release.
- No compliance claims. This is the capability a compliance effort would rest on, not that effort.

## Decisions

**1. Identity travels with the artifact, not in a database row.** Each validated model is exported with a model card (`pd_model_card.json`, `survival_model.json`) holding its version, training window, algorithm, hyperparameters, validation results, limitations and the SHA-256 of the ONNX file. Card and artifact are packaged in the same jar. *Why not the `model_version` table:* a row in a database and a file in a jar are two things that can disagree, and the original design's own risk list said so (someone copies an ONNX file without registering it). Putting the description inside the deployable removes the second source of truth: one image is one set of code, models and cards. Rolling back a release rolls the model and its description back together, with no "mark the previous row active" step to forget. *What this gives up:* there is no SQL query for "every version ever deployed". The history is the git history of the cards, and the versions actually used are in the `model_version` columns of the results they produced.

**2. A checksum mismatch stops the service.** `ModelRegistry` (and `SurvivalModel` for its own artifact) hashes the bytes loaded from the classpath and compares them with the card. On a mismatch the application does not start. *Why not log a warning and continue,* as first planned by analogy with the fail-open cache and rate limiter: those degrade speed or protection but leave the numbers right. A model that is not the one its card describes makes every score, explanation and loss figure unattributable, and a warning in a log is not a control. Not answering is the safer failure.

**3. Traceability is a version string on each result, not a foreign key.** `loan_score.model_version`, `model_invocation_events.model_version` and the portfolio-risk tables record which model produced the row, as the model's id and the version from its card (`pd_24m@2026.10.01-1308`); `ScoreResponse` and `ExpectedLossResponse` return it. It follows from decision 1: there is no table to point at, and the card behind a version holds the checksum, so a version identifies exact bytes. The column is nullable on the two existing tables, and rows written before this change stay `NULL`, meaning "predates version tracking" rather than a made-up value.

**4. Validation happens at export, against realized outcomes; drift is monitored at run time.** The planned monthly job would have joined operational scores to realized defaults. This deployment has no servicing feed, so its portfolio never acquires outcomes and the job would have computed an AUC over zero loans indefinitely. Realized outcomes do exist in the historical data the models are trained on, so that is where validation is done: the export evaluates each model on vintages held out in time, writes the result into the card, and for the survival model writes a vintage-by-vintage backtest of predicted against realized cumulative default and prepayment (`survival_backtest.json`, Aalen-Johansen estimates of the realized side). What *can* be measured on a live portfolio without outcomes is whether it still resembles the training population. `DriftService` computes the population stability index per input and for the score. PSI is biased upward in small samples (about `(bins - 1) / n` from sampling alone), so each value is reported with that floor and judged after subtracting it; otherwise every small portfolio would appear to drift.

**5. The inventory lists the models that are not validated.** LGD, the regime model, early warning and the trajectory model have no card yet. They are in `GET /v1/models` with their checksum, tier and a statement of what is missing, marked `validated: false`. An inventory that only lists the well-documented models is not an inventory.

**6. Model identity is observable.** Each entry is exported as `model_info{model, version, sha256} 1`. The Prometheus rule `ReplicasServeDifferentModels` fires when replicas hold different artifacts of one model for 15 minutes, the state in which the same loan gets a different score depending on which replica answers.

## Risks / Trade-offs

- **[Risk]** Validation in the card is a snapshot from export time; it does not move as new outcomes arrive. → The drift endpoint is the run-time signal, and re-running the export against newer performance data produces a new card. A scheduled re-validation needs an outcome feed (see Open Questions).
- **[Risk]** A version string is only as unique as the export clock. → Versions carry the minute of the export, and the card's checksum, not the version, is what the startup check trusts.
- **[Trade-off]** Deploying a new model means building a new image. For a service whose model changes a few times a year, and where a model change must be reviewable and reversible as a unit, that is the intended property rather than a cost.
- **[Trade-off]** Four of the six statistical models are still unvalidated. The inventory says so; closing those gaps is model work, not registry work.

## Migration

1. V26 adds nullable `model_version` to `loan_score` and `model_invocation_events`; the portfolio-risk tables are created with it. No backfill.
2. `export_model.py` and `export_survival_model.py` write the card, drift reference and backtest alongside the ONNX file.
3. The service verifies checksums at startup and stamps the version on new results.
4. Rollback is redeploying the previous image. The added columns are nullable and can stay.

## Open Questions

- When a deployment has a servicing feed, a scheduled comparison of stored scores with realized outcomes becomes possible and worth building. The `model_version` column on `loan_score` is what it would group by.
- The drift report covers the PD model only. The survival model's inputs overlap heavily with it, but its macro drivers need their own reference.
