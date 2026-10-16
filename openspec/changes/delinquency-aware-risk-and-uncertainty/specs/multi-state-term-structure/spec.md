## ADDED Requirements

### Requirement: Exact joint projection
The engine SHALL carry the joint probability of being active in each (macro regime, delinquency state) pair forward month by month and SHALL compute default and prepayment probabilities as exact expectations over all regime paths, with no random sampling.

#### Scenario: Brute-force agreement
- **WHEN** a short horizon is projected both by the engine and by enumerating every regime path and applying the state recursion within each path
- **THEN** the results agree to 1e-12

### Requirement: Probability conservation
In every month, the probabilities of being active (summed over states), defaulted and prepaid SHALL sum to 1.

#### Scenario: Conservation over a full term
- **WHEN** a loan is projected to maturity from any starting state
- **THEN** active plus defaulted plus prepaid equals 1 within 1e-12 in every month

### Requirement: Monotone starting-state ordering
For otherwise identical loans, lifetime default probability SHALL be non-decreasing in the starting state (current, 30, 60 days past due).

#### Scenario: Ordering
- **WHEN** two identical loans differ only in starting state
- **THEN** the loan in the worse state has lifetime PD greater than or equal to the other's

### Requirement: Fallback preserves existing results
A loan with no delinquency data, or a current loan with no history, SHALL be projected by the existing engine and SHALL produce results identical to today's, bit for bit.

#### Scenario: No delinquency data
- **WHEN** a request carries no delinquency fields
- **THEN** the response figures equal those the existing engine produces and the response reports the existing model as used

### Requirement: Scenario and loss handling shared with the existing engine
Scenario paths, exposure, loss given default (including the collateral floor) and discounting SHALL be computed by the same logic as the existing engine, so that identical hazards produce identical losses.

#### Scenario: Equivalent inputs
- **WHEN** the multi-state engine is given a state model whose current-state default and prepayment probabilities equal the existing hazards and whose delinquent transitions are disabled
- **THEN** its expected loss equals the existing engine's within 1e-9

### Requirement: Staging uses the projected state
IFRS 9 staging for a loan SHALL use its supplied delinquency state (30+ days past due is stage 2 and 90+ is stage 3) and SHALL otherwise use the existing rules.

#### Scenario: 30 days past due
- **WHEN** a loan is supplied at 30 days past due
- **THEN** it is staged 2 regardless of its projected PD

### Requirement: Deterministic on any thread count
Portfolio projections using the multi-state engine SHALL give the same totals to the bit on any number of threads.

#### Scenario: Thread independence
- **WHEN** the same portfolio is projected on 1 and on 8 threads
- **THEN** all totals are bit-identical

### Requirement: Independent golden contract
A Python reference implementation SHALL produce fixtures for the multi-state engine, and the Java engine SHALL match them to 1e-6; after any re-export the reference SHALL be re-run by the owner and the golden test re-run.

#### Scenario: Golden match
- **WHEN** the golden test runs against committed fixtures
- **THEN** every projected quantity matches within 1e-6

### Requirement: Out-of-range reporting
A projection SHALL report the months in which any driver leaves the model's training range, as the existing engine does.

#### Scenario: Unemployment above range
- **WHEN** a scenario drives unemployment above the training maximum in month 14
- **THEN** the response lists month 14 as out of range for that driver
