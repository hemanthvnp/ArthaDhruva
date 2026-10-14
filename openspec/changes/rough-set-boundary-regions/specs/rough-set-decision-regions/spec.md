## ADDED Requirements

### Requirement: Three-way classification of a stressed risk metric
The system SHALL classify a loan's stressed risk metric against a caller-supplied threshold as exactly one of `CERTAIN_HIGH`, `CERTAIN_LOW` or `BOUNDARY`, computed over a granule of scenario variants. A member is "high" when its metric is greater than or equal to the threshold. With tolerance `beta` and `k` high members out of `n`, the region SHALL be `CERTAIN_HIGH` when `k/n >= 1 - beta`, `CERTAIN_LOW` when `k/n <= beta`, and `BOUNDARY` otherwise. The supported metrics SHALL be `PD_12M`, `PD_LIFETIME` and `ECL_LIFETIME_RATE` (lifetime expected credit loss divided by exposure).

#### Scenario: Every member is high
- **WHEN** all `n` members have a metric at or above the threshold
- **THEN** the region is `CERTAIN_HIGH` for any tolerance in `[0, 0.5)`

#### Scenario: Every member is low
- **WHEN** no member has a metric at or above the threshold
- **THEN** the region is `CERTAIN_LOW` for any tolerance in `[0, 0.5)`

#### Scenario: Mixed members under strict tolerance
- **WHEN** at least one member is high, at least one is low, and the tolerance is 0
- **THEN** the region is `BOUNDARY`

#### Scenario: A single outlier under a non-zero tolerance
- **WHEN** 31 of 32 members are high and the tolerance is 0.05
- **THEN** the region is `CERTAIN_HIGH`, and the response reports `k = 31`, `n = 32` and the tolerance used

### Requirement: Rough-set invariants hold
The system SHALL guarantee, for any inputs, that the set of loans classified `CERTAIN_HIGH` is a subset of the loans that are high in at least one member (the upper approximation), that `CERTAIN_HIGH` and `CERTAIN_LOW` are disjoint, that raising the threshold never moves a loan from `CERTAIN_LOW` to `CERTAIN_HIGH`, and that a tolerance of 0 reproduces the strict lower and upper approximations.

#### Scenario: Monotone in the threshold
- **WHEN** the same granule is classified at thresholds `t1 < t2`
- **THEN** no loan is `CERTAIN_LOW` at `t1` and `CERTAIN_HIGH` at `t2`, and no loan is `CERTAIN_HIGH` at `t2` and `CERTAIN_LOW` at `t1` unless it is `BOUNDARY` in between

#### Scenario: Larger tolerance never shrinks the decided set
- **WHEN** the same granule is classified at tolerances `beta1 < beta2`
- **THEN** every loan decided (`CERTAIN_HIGH` or `CERTAIN_LOW`) at `beta1` keeps the same decision at `beta2`

### Requirement: Perturbation granule definition
For the `PERTURBATION` granule kind, the system SHALL build `n` members of one target scenario, where member 0 is the unperturbed target and each other member scales `unemploymentShockPp`, `hpiShockPct` and `rateShockBp` by a factor in `[1 - shockBand, 1 + shockBand]` and shifts `unemploymentShockMonths`, `hpiShockMonths` and `stressedMonths` by an integer in `[-timingBandMonths, +timingBandMonths]`. Every member SHALL be clamped to the `Scenario` validation ranges, and a target whose shocks are all zero SHALL produce members that differ only through the parameters that are non-zero.

#### Scenario: Member 0 is the target
- **WHEN** a granule is built for the SEVERELY_ADVERSE scenario
- **THEN** member 0 equals SEVERELY_ADVERSE exactly, and the classification of a request with `n = 1` equals the unperturbed result

#### Scenario: Default bands
- **WHEN** no band is given
- **THEN** shock magnitudes vary by ±20% and shock timing by ±3 months, and the response echoes both

#### Scenario: Members stay valid
- **WHEN** a band would push a parameter outside its validation range
- **THEN** the member is clamped to the range and is still a valid `Scenario`

#### Scenario: Baseline has nothing to perturb in magnitude
- **WHEN** the target is BASELINE
- **THEN** the unemployment, house-price-decline and rate shocks stay at their baseline values in every member, and only non-zero parameters vary

### Requirement: Scenario-set granule
For the `SCENARIO_SET` granule kind, the system SHALL use the caller-listed built-in scenarios as the members, so that `CERTAIN_HIGH` means the metric is high under every listed scenario, and SHALL label the result as robustness across the listed scenarios.

#### Scenario: High under every listed scenario
- **WHEN** the listed scenarios are ADVERSE and SEVERELY_ADVERSE and both exceed the threshold
- **THEN** the region is `CERTAIN_HIGH` and the response lists the scenarios used

#### Scenario: Unknown scenario name
- **WHEN** a listed scenario name is not a built-in scenario
- **THEN** the request is rejected with a message naming the valid scenarios

### Requirement: Deterministic and thread-independent
The system SHALL derive member `i` from a counter-based generator seeded from the request, with no shared generator state, so that the same request returns the same members, metrics and region on any thread count. When no seed is supplied the seed SHALL be a stable hash of the canonicalised request, and the seed used SHALL be returned. A derived seed and any seed the caller supplies SHALL lie within ±(2^53 − 1), so that the value shown in a response survives a round trip through a JavaScript number and can be sent back to reproduce the result.

#### Scenario: Same request, same answer
- **WHEN** an identical request is sent twice
- **THEN** both responses carry the same seed, members' metric range and region

#### Scenario: Seed survives a round trip
- **WHEN** a response's seed is read as a JavaScript number and sent back in a request
- **THEN** it is the same integer, and a supplied seed outside ±(2^53 − 1) is refused with a message naming `seed`

#### Scenario: Evaluation order does not matter
- **WHEN** members are evaluated in a different order or on a different number of threads
- **THEN** the region and the metric statistics are unchanged

### Requirement: Spread statistics
The system SHALL report, for every classified loan, `k`, `n`, the tolerance, the minimum, median and maximum of the metric across the granule, and the boundary width (maximum minus minimum).

#### Scenario: Width is non-negative and consistent
- **WHEN** any loan is classified
- **THEN** the width equals the maximum minus the minimum and is at least 0, and the median lies between them

### Requirement: Extrapolation qualifies the region
The system SHALL set `extrapolated` to true and list the drivers when any granule member pushes a driver that the scenario moves (unemployment, unemployment change, house-price change, rate incentive, or a mark-to-market LTV above its trained ceiling) outside the range the model was trained on, and SHALL NOT change the region because of it. Extrapolation that occurs in every scenario including the baseline (loan age beyond the training window, amortisation taking mark-to-market LTV below its floor) SHALL NOT set the flag.

#### Scenario: Severe stress leaves the trained range
- **WHEN** a member drives house-price change or unemployment beyond the trained limit
- **THEN** the result has `extrapolated = true` with the driver, the trained limit and the first month, and the region is the one the classification rule gave

#### Scenario: Structural extrapolation alone
- **WHEN** the only extrapolation is loan age beyond the training window, as for any 30-year loan under the baseline
- **THEN** `extrapolated` is false

### Requirement: Input validation
The system SHALL reject, with a 422 response and a message that names the field, a threshold outside the metric's valid range, a tolerance outside `[0, 0.5)`, a member count outside the allowed range, a band outside its allowed range, an unknown metric or granule kind, and `regimeDraws` when regime draws are not available.

#### Scenario: Tolerance too large
- **WHEN** the tolerance is 0.5 or more
- **THEN** the request is rejected with a message that names the tolerance and its allowed range

#### Scenario: Regime draws unavailable
- **WHEN** the request asks for `regimeDraws` and the posterior-draw capability is not present
- **THEN** the request is rejected with a message saying so, and no draws are silently skipped
