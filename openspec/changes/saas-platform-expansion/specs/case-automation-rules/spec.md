## ADDED Requirements

### Requirement: Automation rule definition
An organization admin SHALL be able to define an automation rule consisting of a triggering event type, a condition, and one or more actions, scoped to their own organization.

#### Scenario: Admin defines a risk-based auto-flag rule
- **WHEN** an admin creates a rule with trigger "loan scored", condition "calibrated risk > 0.20", and action "flag case"
- **THEN** the rule is stored for that organization and evaluated against future matching events only

### Requirement: Rule evaluation on domain events
When a domain event occurs, the system SHALL evaluate all active rules for that organization whose trigger matches the event type, in a defined order, and execute the actions of every rule whose condition matches.

#### Scenario: Matching rule executes its action
- **WHEN** a loan is scored above the threshold configured in an active auto-flag rule
- **THEN** the corresponding case is flagged automatically, without any user interaction

#### Scenario: Non-matching rule does not execute
- **WHEN** a loan is scored below the threshold configured in an active auto-flag rule
- **THEN** the case is not flagged by that rule

#### Scenario: One event can trigger multiple rules
- **WHEN** an event matches the trigger of more than one active rule for an organization
- **THEN** every matching rule's actions are executed, each independently of the others' success or failure

### Requirement: Supported automation actions
The system SHALL support, at minimum, the actions: assign case to a user, flag case, and send a notification.

#### Scenario: Auto-assign action
- **WHEN** a rule's action is "assign to user X" and its condition matches
- **THEN** the case's `assignedTo` is set to user X, triggering the existing case-assignment notification

### Requirement: Rule failure isolation
A failure while evaluating or executing one rule SHALL NOT prevent other rules, or the original triggering request, from completing.

#### Scenario: One rule throws an error
- **WHEN** one automation rule's action fails during execution
- **THEN** the error is logged, other rules for the same event still execute, and the original request that triggered the event still succeeds
