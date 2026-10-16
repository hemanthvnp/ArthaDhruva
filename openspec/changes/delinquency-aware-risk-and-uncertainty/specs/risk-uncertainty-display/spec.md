## ADDED Requirements

### Requirement: Range shown with the estimate
Wherever a lifetime PD or expected loss has an interval, the console SHALL show the point estimate with its range and SHALL NOT show the point estimate alone.

#### Scenario: Interval available
- **WHEN** a response includes an interval for lifetime PD
- **THEN** the page shows the estimate and its lower and upper bounds together

### Requirement: Method note
The console SHALL show a short method note beside each interval stating "regime uncertainty plus historical calibration error" and how many draws and backtest vintages it rests on.

#### Scenario: Note content
- **WHEN** a user opens the method note
- **THEN** it names the method, the draw count and the vintage count

### Requirement: Fallback is visible
When the delinquency model did not run, the page SHALL state that the loan's status was not used and why.

#### Scenario: Fallback
- **WHEN** a response reports the existing model as used because history was incomplete
- **THEN** the page shows that message next to the result

### Requirement: Delinquency input on the loan form
The Lifetime Risk form SHALL let an analyst enter a loan's current delinquency status and history with validation matching the API, and SHALL send them only when provided.

#### Scenario: Optional fields blank
- **WHEN** the analyst leaves the delinquency fields empty
- **THEN** the request contains none of the new fields

### Requirement: Comparison of starting states
The Lifetime Risk page SHALL offer a comparison of the same loan from current, 30 and 60 days past due, showing the projected lifetime PD and expected loss for each.

#### Scenario: Compare states
- **WHEN** the analyst requests the comparison
- **THEN** three results are shown, ordered by starting state, with their intervals

### Requirement: Portfolio model split
The Portfolio Risk page SHALL show how many loans used each model when a run mixes them.

#### Scenario: Mixed run
- **WHEN** a run result reports counts per model
- **THEN** the page displays both counts

### Requirement: Accessible and within budget
New components SHALL be keyboard accessible with text alternatives for any chart range, and the initial-bundle budget check SHALL still pass.

#### Scenario: Bundle budget
- **WHEN** the frontend is built
- **THEN** `npm run check:bundle` passes without raising the budget

### Requirement: Component and page tests
The interval component, fallback message and comparison SHALL have unit tests, and `npm run lint`, `npm test` and `npm run build` SHALL pass.

#### Scenario: CI order
- **WHEN** lint, tests, build and the bundle check run in CI order
- **THEN** all four pass
