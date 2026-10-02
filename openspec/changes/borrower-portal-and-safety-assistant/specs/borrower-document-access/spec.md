## ADDED Requirements

### Requirement: Borrower can view documents on their own linked loan(s)
The system SHALL allow a `CLIENT` to list and download attachments belonging to a loan in their own
`User.getLoanIds()`, and SHALL NOT allow a `CLIENT` to view attachments for any other loan.

#### Scenario: Listing own documents
- **WHEN** a `CLIENT` requests documents for one of their own linked loans
- **THEN** the attachments stored for that loan are returned

#### Scenario: Requesting another loan's documents
- **WHEN** a `CLIENT` requests documents for a loan not in their own `loanIds`, including a loan belonging to
  another tenant
- **THEN** the request is rejected and no attachment metadata or content is returned

### Requirement: Borrower can upload a document to their own linked loan(s)
The system SHALL allow a `CLIENT` to upload an attachment to a loan in their own `User.getLoanIds()`, using
the existing attachment storage and validation (file type/size limits, malware scanning), and SHALL NOT allow
upload to any other loan.

#### Scenario: Uploading a document
- **WHEN** a `CLIENT` uploads a file to one of their own linked loans
- **THEN** it is stored and scanned the same way an analyst-uploaded attachment is, and appears in that
  loan's document list

#### Scenario: Uploading to another loan
- **WHEN** a `CLIENT` attempts to upload to a loan not in their own `loanIds`
- **THEN** the request is rejected before any file is stored

### Requirement: Borrower document endpoints are independent of case-scoped analyst endpoints
The system SHALL expose borrower document access through endpoints scoped to the caller's own loans, with no
`caseId` parameter and no dependency on the caller having access to any `LoanCase`.

#### Scenario: No case required
- **WHEN** a `CLIENT`'s loan has no open case
- **THEN** that `CLIENT` can still list and upload documents for that loan
