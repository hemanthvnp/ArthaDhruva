## ADDED Requirements

### Requirement: Validation against realized outcomes, versioned with the model
The system SHALL evaluate each validated model against realized loan outcomes on data held out in time, at export, and SHALL ship the results with the model so that they describe exactly the artifact in use.

#### Scenario: Out-of-time validation in the card
- **WHEN** a validated model is exported
- **THEN** its card records the model's discrimination and calibration on vintages that were not used to fit it, alongside the in-time holdout figures

#### Scenario: Vintage backtest of the survival model
- **WHEN** the survival model is exported
- **THEN** a backtest is written comparing, for each held-out vintage, the predicted cumulative default and prepayment curves with the realized ones

#### Scenario: Reading the backtest
- **WHEN** an analyst requests `GET /v1/models/survival/backtest`
- **THEN** the backtest shipped with the running model is returned

### Requirement: Drift monitoring of the scored population
The system SHALL report, on request, how far the portfolio currently being scored has moved from the population the PD model was trained on, for each model input and for the score itself.

#### Scenario: Drift report
- **WHEN** an analyst requests `GET /v1/models/pd_24m/drift`
- **THEN** the response gives the population stability index of every input and of the score against the training reference exported with the model, together with the model version and the number of loans compared

#### Scenario: Small portfolios
- **WHEN** the portfolio is small enough that sampling alone produces a visible index
- **THEN** each index is reported with its noise floor, `(bins - 1) / n`, and is classified as stable, moderate or significant after subtracting that floor

#### Scenario: A value the training data never saw
- **WHEN** a loan carries a category that does not occur in the training reference
- **THEN** it is counted in the report rather than dropped, and the report is still produced

### Requirement: No outcome-based monitoring without outcomes
The system SHALL NOT report run-time accuracy figures for a deployment that has no realized outcomes for the loans it scored.

#### Scenario: A deployment without a servicing feed
- **WHEN** the loans a deployment has scored have no recorded outcomes
- **THEN** no backtest of those scores is computed or displayed; the model's accuracy is the out-of-time validation in its card, and the run-time signal is the drift report
