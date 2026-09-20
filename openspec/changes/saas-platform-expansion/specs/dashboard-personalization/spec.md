## ADDED Requirements

### Requirement: Configurable dashboard widgets
A user SHALL be able to choose which widgets appear on their own dashboard and in what arrangement, independent of other users in the same organization.

#### Scenario: User customizes their dashboard
- **WHEN** a user adds a "my open cases" widget and removes the "portfolio KPI" widget
- **THEN** their next dashboard view reflects that configuration, and other users' dashboards in the same organization are unaffected

### Requirement: Per-event notification preferences
A user SHALL be able to configure, per notification-triggering event type, whether they receive it instantly, in a periodic digest, or not at all.

#### Scenario: User opts into digest mode for note notifications
- **WHEN** a user sets "new note added" notifications to "daily digest"
- **THEN** individual note-added notifications are not delivered instantly to that user, and are instead included in their next daily digest

#### Scenario: User disables a notification type
- **WHEN** a user sets a notification type to "off"
- **THEN** no notification of that type is created for that user going forward, while other users' preferences for the same event type are unaffected
