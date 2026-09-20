## ADDED Requirements

### Requirement: Composable filter queries across loans, cases, and notes
A user SHALL be able to combine multiple filter criteria (e.g., status, flagged, property state, risk band, date range) into a single query against their organization's loans, cases, or notes, without each combination requiring a dedicated API endpoint.

#### Scenario: Combined filter query
- **WHEN** a user queries cases with status "ESCALATED" AND flagged=true AND property state "CA"
- **THEN** the system returns exactly the cases in that organization matching all three criteria simultaneously

#### Scenario: Query is scoped to the caller's tenant
- **WHEN** any advanced-search query is executed
- **THEN** results never include rows belonging to a different organization, regardless of the filter criteria supplied

### Requirement: Bulk case update
A user SHALL be able to apply a status, assignment, or flag update to multiple loan cases in a single operation.

#### Scenario: Bulk assign
- **WHEN** a user selects multiple cases and applies a bulk "assign to user X" action
- **THEN** every selected case's `assignedTo` is updated, and each update independently triggers the existing case-assignment notification

#### Scenario: Partial failure in a bulk operation is reported per item
- **WHEN** a bulk update includes one case ID that does not exist or does not belong to the caller's organization
- **THEN** the valid cases are updated and the invalid case is reported individually as failed, without failing the entire batch
