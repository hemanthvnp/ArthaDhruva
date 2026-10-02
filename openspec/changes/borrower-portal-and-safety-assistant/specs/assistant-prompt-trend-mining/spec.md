## ADDED Requirements

### Requirement: Borrower-assistant prompts are mined only as aggregated, anonymized trends
The system SHALL surface patterns across borrower-assistant prompts to analysts only as aggregated,
anonymized trend signals, and SHALL NOT surface any individual borrower's prompt or conversation to an
analyst as a product of this capability.

#### Scenario: A trend is surfaced
- **WHEN** a sufficient number of borrowers across the tenant describe a similar pattern (e.g. a particular
  scam) within a reporting window
- **THEN** analysts see an aggregated signal (a topic, a count, a trend over time) with no borrower-identifying
  detail and no verbatim individual prompt text

#### Scenario: A single borrower's prompt is never exposed via this path
- **WHEN** an analyst views trend-mining output
- **THEN** no output traceable to one specific borrower's conversation is presented, regardless of cohort size

### Requirement: Trend mining reuses the existing topic-clustering approach
The system SHALL build this capability on the existing scheduled topic-clustering job (`InsightService`,
`TopicModel`) applied to a new data source, rather than introducing separate clustering infrastructure.

#### Scenario: Shared clustering approach
- **WHEN** the trend-mining job runs
- **THEN** it uses the same clustering method already used for case-note topics, applied to
  borrower-assistant prompts
