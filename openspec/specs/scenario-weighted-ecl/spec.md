# scenario-weighted-ecl Specification

## Purpose

Combine the expected credit loss of several macro scenarios into one probability-weighted figure, for a single loan or the latest portfolio run, with validated weights and a console view of how the figure was made.

## Requirements

### Requirement: Weights are validated before anything is computed
The system SHALL accept scenario weights as a map from scenario name to a number, and SHALL reject the request with 422 and a message naming the problem when a weight is negative or not finite, when the weights do not sum to 1 within 1e-6, when the map is empty, or when a name is not one of the scenarios being compared. Names SHALL be matched case-insensitively after trimming. A compared scenario that is not named SHALL carry weight 0.

#### Scenario: weights that do not sum to one
- **WHEN** a comparison of BASELINE and ADVERSE is requested with weights `{"BASELINE": 0.5, "ADVERSE": 0.3}`
- **THEN** the response is 422 and the message states the sum that was received and that it must be 1

#### Scenario: a weight for a scenario that is not compared
- **WHEN** a comparison of BASELINE and ADVERSE is requested with weights `{"BASELINE": 0.5, "RATES_UP_200": 0.5}`
- **THEN** the response is 422 and the message names RATES_UP_200 and lists the compared scenarios

#### Scenario: a scenario left out of the weights
- **WHEN** BASELINE, ADVERSE and SEVERELY_ADVERSE are compared with weights `{"baseline": 0.7, "Adverse": 0.3}`
- **THEN** the request succeeds, the echoed weights are BASELINE 0.7, ADVERSE 0.3 and SEVERELY_ADVERSE 0, and the weighted figures equal those of BASELINE and ADVERSE alone

### Requirement: Weighted figures are the weighted sum of the scenarios' own figures
For weights `w_s` the system SHALL report, for each of the 12-month PD, lifetime PD, 12-month ECL, lifetime ECL, IFRS 9 allowance and CECL allowance, the value `sum_s w_s x figure_s`, where `figure_s` is exactly the figure the same request reports for scenario `s`. It SHALL report the probability of each stage as the total weight of the scenarios in which the loan (or, for a portfolio, the share of loans) is in that stage. Sums SHALL be taken in the order the scenarios are compared, so the result does not depend on the order of the weights map.

#### Scenario: all weight on one scenario
- **WHEN** a comparison is requested with weight 1 on ADVERSE
- **THEN** every weighted figure equals ADVERSE's figure exactly, and the stage probabilities are 1 for ADVERSE's stage and 0 for the others

#### Scenario: a weighted figure lies between the scenarios
- **WHEN** BASELINE and SEVERELY_ADVERSE are weighted 0.5 each
- **THEN** each weighted figure equals the mean of the two scenarios' figures to within 1e-12 relative

#### Scenario: order of the weights does not matter
- **WHEN** the same weights are sent with their keys in a different order
- **THEN** the weighted figures are bit-for-bit identical

### Requirement: A single-loan comparison returns weighted figures only when asked
`POST /v1/risk/term-structure/compare` SHALL accept an optional `weights` field. With it, the response SHALL carry a `weighted` object holding the weighted figures, the stage probabilities, the weights used and the method label `SCENARIO_WEIGHTED`. Without it, the response SHALL contain no `weighted` field and SHALL otherwise be identical to the response before this change.

#### Scenario: no weights
- **WHEN** a comparison is requested without `weights`
- **THEN** the response has no `weighted` property and its other fields are unchanged

#### Scenario: weights given
- **WHEN** a comparison of the three stress scenarios is requested with weights 0.5, 0.3 and 0.2
- **THEN** the response carries `weighted` with the six figures, three stage probabilities that sum to 1, the three weights and the method label, and each scenario's own block is the same as without weights

### Requirement: The latest portfolio run can be weighted without running it again
`POST /v1/risk/portfolio/weighted` SHALL take weights over the scenarios of the organisation's latest completed portfolio run and return the weighted exposure-level figures (12-month ECL, lifetime ECL, IFRS 9 allowance, coverage) and the weighted number of loans in each stage, computed from the stored per-scenario results. It SHALL not start a run or project a loan. It SHALL return 404 when the organisation has no stored run, and 422 when a weighted scenario was not part of that run. It SHALL return figures only for the caller's organisation.

#### Scenario: weighting a stored run
- **WHEN** the latest run covered BASELINE, ADVERSE and SEVERELY_ADVERSE and weights 0.5, 0.3 and 0.2 are posted
- **THEN** the response's IFRS 9 allowance equals 0.5, 0.3 and 0.2 times the three stored allowances, and it names the run's date, model version and scenarios

#### Scenario: a scenario the run did not cover
- **WHEN** the latest run covered BASELINE and ADVERSE and a weight is posted for SEVERELY_ADVERSE
- **THEN** the response is 422 and lists the scenarios the run covered

#### Scenario: no run yet
- **WHEN** an organisation with no stored portfolio run posts weights
- **THEN** the response is 404

### Requirement: The console shows the weighted figures and how they were made
The Lifetime Risk comparison and the Portfolio Risk page SHALL offer a weight for each scenario shown, a preset of 50 / 30 / 20 for BASELINE, ADVERSE and SEVERELY_ADVERSE labelled as illustrative, and a control that is disabled with a stated reason while the weights do not sum to 100%. The weighted figures SHALL be shown beside the scenarios' figures with the weights used and a one-line statement that each scenario stages loans on its own path.

#### Scenario: weights that do not add up
- **WHEN** the entered weights sum to 90%
- **THEN** the apply control is disabled and the page says the weights sum to 90% and must sum to 100%

#### Scenario: applying the preset
- **WHEN** the user applies the preset on a comparison of the three stress scenarios
- **THEN** a weighted row appears with the six figures and the label "50% baseline, 30% adverse, 20% severely adverse"
