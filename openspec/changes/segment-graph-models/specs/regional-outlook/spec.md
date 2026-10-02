## ADDED Requirements

### Requirement: A forecast ships only if it beats declared baselines out of time
The system SHALL forecast each state's change in standardised delinquency ratio three months ahead, and SHALL ship a forecast only if it beats a no-change baseline and an own-history autoregression in a rolling-origin backtest, under a rule that was recorded before the backtest was run.

#### Scenario: Rolling origin
- **WHEN** the backtest runs
- **THEN** origins are monthly from January 2021 with an expanding training window, and the forecast at each origin uses only data available at that origin

#### Scenario: Significance
- **WHEN** candidates are compared
- **THEN** the comparison is a Diebold-Mariano test on the cross-state mean squared error per origin, with a HAC variance at lag 2 and the Harvey-Leybourne-Newbold correction, one-sided at 5%

#### Scenario: Nothing beats the baselines
- **WHEN** no candidate beats both baselines
- **THEN** no forecast file is produced, and the result is recorded in `MODELS.md` under what was tried and rejected

#### Scenario: The rule is not edited after the fact
- **WHEN** the decision rule changes after results have been seen
- **THEN** the change is recorded in the design as a dated amendment

### Requirement: The graph's contribution is measured by ablation
The system SHALL measure what graph features add by comparing each model class with and without them, on the same origins and settings.

#### Scenario: Same model class
- **WHEN** a graph-feature model is evaluated
- **THEN** it is compared with the same model class and settings without graph features

#### Scenario: No leakage from the graph
- **WHEN** graph features are used in the backtest
- **THEN** they come from the graph learned on data through December 2016, at every origin

#### Scenario: The graph does not help
- **WHEN** the graph version does not beat the no-graph version at 5%, or has the lower cross-sectional rank correlation
- **THEN** the shipped model is the one without graph features, and the card says that the graph did not help

### Requirement: A forecast comes with its uncertainty and its track record
The system SHALL ship, with each forecast, an interval and the record of how the forecaster performed in the backtest.

#### Scenario: Intervals
- **WHEN** a forecast file is exported
- **THEN** each state has an 80% interval from split-conformal residuals, and the file reports the backtest coverage of those intervals

#### Scenario: Identity
- **WHEN** a forecast file is exported
- **THEN** it states the as-of month, the model, a version and a checksum, and the backtest candidates with their errors and rank correlations
