# rough-set-portfolio-regions Specification

## Purpose

Region classification as an optional stage of a portfolio run: aggregates, a deterministic merge, a boundary list for review, tenant isolation and bounded cost.

## Requirements

### Requirement: Regions as an optional stage of a portfolio run
When a portfolio run request carries a `decision` block, the system SHALL classify every loan in the run under one target scenario (default: the most severe scenario requested) as part of that run, and SHALL keep the run's existing behaviour: a `202` with a run row, a heartbeat, one active run per organisation, polling served by any replica, and a dead worker's run reported failed. A run without a `decision` block SHALL behave exactly as before.

#### Scenario: Run with a decision block
- **WHEN** a portfolio run is started with a metric, a threshold and a tolerance
- **THEN** the response is `202` with a run row, and the completed run holds a region for every loan and scenario

#### Scenario: Run without a decision block
- **WHEN** a portfolio run is started with no `decision` block
- **THEN** the run, its snapshot and its loan listing are unchanged from today

#### Scenario: Second active run
- **WHEN** an organisation already has an active run and starts another
- **THEN** the second request is refused in the same way as today

### Requirement: Portfolio aggregates
The system SHALL report, per scenario, the number of loans and the exposure in each region, each region's share of the book, the boundary exposure by segment, and an expected-loss range whose lower end sums only `CERTAIN_HIGH` loans' loss at their minimum across the granule and whose upper end sums every loan that is not `CERTAIN_LOW` at its maximum across the granule.

#### Scenario: Regions partition the book
- **WHEN** a run completes
- **THEN** the loan counts of the three regions sum to the number of loans, and the exposures sum to total exposure to within floating-point tolerance

#### Scenario: Range brackets the point estimate
- **WHEN** the tolerance is 0 and the lifetime expected loss of the loans that are high under the unperturbed scenario is summed
- **THEN** it lies between the lower and upper ends of the range

### Requirement: Deterministic merge
The system SHALL produce identical per-loan regions and portfolio aggregates for the same inputs and seed on any thread count, by using counter-based member seeds and merging results in loan order.

#### Scenario: One thread versus many
- **WHEN** the same run is computed on one thread and on many threads
- **THEN** every loan's region and every aggregate are identical

### Requirement: Boundary list for review
The system SHALL expose each loan's region on the loan listing and SHALL support filtering the listing to a region, so that the boundary loans can be listed and exported by an analyst.

#### Scenario: Filter to the boundary
- **WHEN** the loan listing is requested with the region filter set to `BOUNDARY`
- **THEN** only boundary loans are returned, each with its metric range and `extrapolated` flag

#### Scenario: Export includes the region
- **WHEN** the loan export is requested for a run that has regions
- **THEN** each row includes the region, the metric minimum and maximum, and the tolerance

### Requirement: Tenant isolation
The system SHALL keep stored regions under row-level security like every tenant table, and SHALL NOT change the grants on the audit table.

#### Scenario: Cross-tenant read
- **WHEN** one organisation queries regions belonging to another
- **THEN** no rows are returned, tested as the non-owner application role

### Requirement: Bounded cost
The system SHALL cap the portfolio granule size and refuse a run whose loans multiplied by members exceeds a configured limit, with a message that gives the product and the limit, instead of running for an unbounded time.

#### Scenario: Run too large
- **WHEN** loans times members exceeds the configured limit
- **THEN** the run is refused before any work starts, with a message naming the product and the limit
