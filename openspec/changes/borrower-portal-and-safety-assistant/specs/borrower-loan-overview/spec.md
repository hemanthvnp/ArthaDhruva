## ADDED Requirements

### Requirement: Borrower sees a plain-language risk status, never the raw probability
The system SHALL present a `CLIENT` with a plain-language risk band for each of their linked loans, derived
from the loan's calibrated default probability, and SHALL NOT include the raw probability, model version, or
feature-attribution explanation in any response reachable by a `CLIENT`.

#### Scenario: A scored loan shows a status band
- **WHEN** a `CLIENT` requests their linked loans and one has a computed score
- **THEN** the response includes a risk band (e.g. `LOW`/`MEDIUM`/`HIGH`) and omits `calibratedProbability`,
  `rawProbability`, `modelVersion`, and any explanation fields

#### Scenario: An unscored loan shows no status
- **WHEN** a `CLIENT`'s linked loan has not yet been scored
- **THEN** the response indicates no status is available, without error

### Requirement: Borrower sees a trend summary, not a numeric delta
The system SHALL present a short, plain-language trend statement alongside the risk band rather than a
numeric change figure.

#### Scenario: Trend is textual
- **WHEN** a `CLIENT` views their loan's status
- **THEN** the trend is rendered as a sentence (e.g. "Stable for the last 3 months"), with no percentage or
  numeric delta present in the response

### Requirement: Borrower sees their loan's case status in plain language
The system SHALL show a `CLIENT` whether their loan has an open case and, if so, its status and assigned
team, without exposing case notes or other internal case detail.

#### Scenario: An open case exists
- **WHEN** a `CLIENT`'s linked loan has an open `LoanCase`
- **THEN** the response includes the case's status and assigned team, opened date, and excludes case notes

#### Scenario: No open case
- **WHEN** a `CLIENT`'s linked loan has no open `LoanCase`
- **THEN** the response indicates there are no open items

#### Scenario: Case on another tenant's or borrower's loan is never shown
- **WHEN** a `CLIENT` requests case status
- **THEN** only cases on loans in `User.getLoanIds()` for that caller, within their own tenant, are ever
  considered — no case for any other loan is returned regardless of input

### Requirement: Borrower sees a contact for their loan
The system SHALL present a `CLIENT` with a point of contact for their loan (the case's assigned analyst when
a case is open) and a way to initiate contact.

#### Scenario: Contact shown with an open case
- **WHEN** a `CLIENT`'s linked loan has an open case with an assigned analyst
- **THEN** that analyst's name and a contact affordance are shown

### Requirement: Borrower sees generic risk-factor education, never their own explanation data
The system SHALL present a `CLIENT` with a static, generic description of the categories of information that
influence mortgage risk, and SHALL NOT present any value computed from the borrower's own feature or
explanation data.

#### Scenario: Educational content is generic
- **WHEN** a `CLIENT` views the risk-factors panel
- **THEN** the content lists general factor categories (e.g. payment history, credit utilization) with no
  figures specific to the borrower's own loan

### Requirement: Borrower navigation is scoped to borrower-relevant destinations
The system SHALL present a `CLIENT` with navigation limited to their own loan, documents, notifications, and
account — and SHALL NOT present navigation to analyst-only areas (cases, automation rules, model governance,
or any tenant-wide view).

#### Scenario: Borrower sidebar
- **WHEN** a `CLIENT` is signed in
- **THEN** the navigation shown includes only destinations scoped to their own account and excludes any
  analyst-only destination
