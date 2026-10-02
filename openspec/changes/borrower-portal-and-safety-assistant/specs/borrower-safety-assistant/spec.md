## ADDED Requirements

### Requirement: The borrower assistant is scoped only to the caller's own loan(s)
The system SHALL ground every answer from the borrower assistant in only the caller's own `User.getLoanIds()`
data, resolved server-side, and SHALL NOT accept or honor a client-supplied `loanId` identifying a different
loan.

#### Scenario: Borrower asks about their own loan
- **WHEN** a `CLIENT` sends a question to the borrower assistant
- **THEN** the answer is grounded only in data for loans in that caller's own `loanIds`

#### Scenario: Attempt to reference another loan
- **WHEN** a request to the borrower assistant includes a `loanId` not in the caller's own `loanIds`, or any
  `loanId` at all
- **THEN** the supplied value is ignored; the assistant still answers using only the caller's own loan(s)

### Requirement: The borrower assistant is a separate service from the analyst assistant
The system SHALL implement the borrower assistant as a distinct endpoint and service from
`AssistantController`/`AssistantService`, with its own system prompt, and SHALL NOT share a code path that
could cause the analyst assistant's case-note-grounded context to be used in a borrower-assistant response.

#### Scenario: Independent failure
- **WHEN** the analyst assistant's prompt, access rule, or behavior changes
- **THEN** the borrower assistant's behavior is unaffected, and vice versa

### Requirement: The borrower assistant verifies suspected scam communications
The system SHALL allow a `CLIENT` to describe or paste a message they received and receive an assessment of
whether it has the characteristics of a scam, grounded in facts the system can verify about their own account
(e.g. no missed payments, no outstanding gift-card payment requests).

#### Scenario: Suspicious message flagged
- **WHEN** a `CLIENT` describes a message asking for payment by an unusual method (e.g. gift cards) or
  containing urgency/threat language
- **THEN** the response flags it as having scam characteristics and states the specific reasons

### Requirement: The borrower assistant always offers escalation to a human
The system SHALL present a path to contact the borrower's loan officer alongside every borrower-assistant
response, regardless of the assistant's confidence or verdict.

#### Scenario: Escalation always present
- **WHEN** the borrower assistant returns any answer
- **THEN** a contact affordance for the borrower's loan officer is shown with it, not conditionally hidden

### Requirement: The borrower assistant is reachable from any borrower page without navigation
The system SHALL present the borrower assistant as a persistent control available from any page in the
borrower portal, opening an overlay panel, and SHALL NOT require navigating to a dedicated page or route to
reach it.

#### Scenario: Opening from any page
- **WHEN** a `CLIENT` is on any page within the borrower portal
- **THEN** a control to open the assistant is present, and activating it opens a panel over the current page
  without a page navigation

### Requirement: Borrower assistant calls are audited distinctly from analyst assistant calls
The system SHALL record an audit event for each borrower-assistant call, identifiable as distinct from
analyst assistant audit events.

#### Scenario: Audit trail distinguishes the two assistants
- **WHEN** a borrower-assistant call completes
- **THEN** its audit event is tagged with a type distinct from `AssistantService` calls, so a reviewer can
  filter one from the other
