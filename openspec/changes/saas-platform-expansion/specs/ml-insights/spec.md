## ADDED Requirements

### Requirement: Topic clustering over case notes
The system SHALL periodically cluster an organization's free-text case notes into topics, surfacing the most common themes to analysts.

#### Scenario: Analyst views note topics
- **WHEN** an analyst opens the case-notes insight view
- **THEN** the system displays the current topic clusters for their organization's notes, each with representative example notes

### Requirement: Unsupervised borrower segmentation
The system SHALL group an organization's loans into segments based on unsupervised clustering of loan features, distinct from the existing state-level segment-correlation graph.

#### Scenario: Analyst views borrower segments
- **WHEN** an analyst requests borrower segmentation for their portfolio
- **THEN** the system returns a set of clusters, each with its defining feature characteristics and member loan count

### Requirement: PD score explainability
Every PD score response SHALL include feature-attribution information identifying which input features contributed most to that specific score.

#### Scenario: Score response includes explanation
- **WHEN** a loan is scored via the existing scoring endpoint
- **THEN** the response includes the top contributing features and their relative contribution to the calibrated probability, in addition to the existing raw/calibrated probability fields

#### Scenario: Explanation is loan-specific, not global
- **WHEN** two different loans with different feature values are scored
- **THEN** their returned feature-attribution explanations differ according to each loan's own input values
