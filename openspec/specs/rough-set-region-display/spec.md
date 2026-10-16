# rough-set-region-display Specification

## Purpose

Show a loan's region on Lifetime Risk and a portfolio region view in the console, without false precision and within the bundle budget.

## Requirements

### Requirement: Region badge on Lifetime Risk
The Lifetime Risk page SHALL let the user enter a metric and threshold and SHALL show the loan's region as a badge together with the metric range across the granule drawn against the threshold, and a one-line method note.

#### Scenario: Certain high
- **WHEN** the result is `CERTAIN_HIGH`
- **THEN** the badge says the loan is high under every tested variant and the whole range is shown above the threshold

#### Scenario: Boundary
- **WHEN** the result is `BOUNDARY`
- **THEN** the badge says the answer depends on the stress details, the range straddles the threshold, and `k` of `n` variants are shown as high

### Requirement: No false precision
The console SHALL NOT describe a region as a probability, a confidence level or a guarantee, SHALL show the method label from the API, and SHALL show a visible caution when `extrapolated` is true saying that a narrow boundary under an extrapolated scenario is not evidence of certainty.

#### Scenario: Extrapolated result
- **WHEN** the response has `extrapolated = true`
- **THEN** the page shows the caution and the driver that left the trained range

#### Scenario: Copy review
- **WHEN** the component's text is reviewed
- **THEN** it contains no wording of confidence, probability of being right, or guarantee about the region

### Requirement: Portfolio region view
The Portfolio Risk page SHALL show, per scenario, the share and exposure of the three regions, the expected-loss range, and a list of boundary loans that links to each loan's Lifetime Risk view and can be exported.

#### Scenario: Boundary list
- **WHEN** a run with regions is open
- **THEN** the boundary loans are listed with their metric range and extrapolation flag, sorted by exposure

#### Scenario: Run without regions
- **WHEN** the open run was started without a decision block
- **THEN** the region view is absent and the rest of the page is unchanged

### Requirement: Accessible and within the bundle budget
Region information SHALL NOT rely on colour alone (text and shape accompany it), SHALL use the existing risk-spectrum design tokens, and the initial-bundle budget check SHALL still pass, with the component lazy-loaded if needed.

#### Scenario: Budget
- **WHEN** the bundle check runs after the change
- **THEN** it passes without raising the budget

#### Scenario: Colour-blind reading
- **WHEN** the badge is viewed without colour
- **THEN** the region is still identifiable from its label and icon

### Requirement: Loading, error and empty states
The page SHALL show a loading state while the granule is evaluated, show the server's 422 message when the options are invalid, and show nothing region-related when no threshold has been entered.

#### Scenario: Invalid option
- **WHEN** the server returns 422 for a bad tolerance
- **THEN** the page shows the message beside the field and keeps the previous result visible
