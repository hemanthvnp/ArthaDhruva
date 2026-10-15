## ADDED Requirements

### Requirement: Regime-chain posterior draws
The service SHALL draw regime transition matrices from a Dirichlet posterior over the observed transition counts, project each draw exactly through the engine, and report the spread of the results as regime uncertainty.

#### Scenario: Draws recorded
- **WHEN** an interval is requested
- **THEN** the response states the number of draws used and that only the regime chain was resampled

### Requirement: Reproducible draws
Each draw SHALL be seeded from a fixed function of the request and the draw index, so that the same request produces the same interval on any thread count and any replica.

#### Scenario: Repeat request
- **WHEN** the same request is sent twice, including to different replicas
- **THEN** the interval bounds are identical

#### Scenario: Thread independence
- **WHEN** draws are computed on 1 and on 8 threads
- **THEN** the interval bounds are bit-identical

### Requirement: Empirical calibration band
The service SHALL widen the regime interval by a multiplicative band derived from the dispersion of predicted over realized cumulative incidence across the vintage backtests, per outcome and horizon, and SHALL record the number of vintages behind each band.

#### Scenario: Band provenance
- **WHEN** a response carries an interval
- **THEN** it identifies the backtest the band came from and how many vintages it used

### Requirement: Honest method label
Every interval SHALL carry the label "regime uncertainty plus historical calibration error" and SHALL NOT be described as a statistical confidence interval.

#### Scenario: Label present
- **WHEN** an interval appears in an API response
- **THEN** the method label is present in the same object

### Requirement: Interval validity
For every reported quantity the interval SHALL contain the point estimate, SHALL have non-negative width, and SHALL stay within the quantity's valid range (probabilities within 0 and 1, losses non-negative).

#### Scenario: Probability bounds
- **WHEN** an interval is computed for a lifetime PD near 0 or 1
- **THEN** its bounds are clamped to the range 0 to 1 and still contain the point estimate

### Requirement: Coverage reporting on backtests
The card SHALL report how often the intervals contained the realized backtest outcomes by vintage, with the number of vintages stated and a small-sample caveat.

#### Scenario: Coverage in the card
- **WHEN** the card is generated
- **THEN** it lists coverage per outcome with the count of vintages and the caveat text

### Requirement: Bounded cost
Interval computation SHALL be bounded by a configured maximum number of draws and a time budget, and SHALL return the point estimate with a stated reason if the budget is exceeded.

#### Scenario: Budget exceeded
- **WHEN** a portfolio request would exceed the interval time budget
- **THEN** the response returns point estimates, no interval, and a reason naming the budget

### Requirement: Optional bootstrap ensemble is separate
If a bootstrap ensemble of refit models is added, it SHALL be a separate, labelled component whose measured cost and coverage gain are recorded, and it SHALL NOT change the default interval method without an owner decision.

#### Scenario: Default unchanged
- **WHEN** the ensemble is not enabled
- **THEN** intervals use only the regime draws and the calibration band
