## ADDED Requirements

### Requirement: Tool coverage of the risk API
The server SHALL expose tools for loan lookup, scoring, explanation, term structure (and compare), scenario listing, CVaR, expected loss, portfolio run start and status, portfolio summary, portfolio loans, loss distribution, regime forecast, model list and model detail, each mapped to its `/v1` endpoint.

#### Scenario: Score a loan
- **WHEN** `score_loan` is called with a valid loan payload
- **THEN** the server calls `POST /v1/score` and returns the probability, model version and any warnings as structured content

### Requirement: Session-cookie authentication with CSRF
The server SHALL authenticate using a session cookie supplied by environment, SHALL send the CSRF header derived from the CSRF cookie on mutating requests, and SHALL never persist or log either value.

#### Scenario: Mutating request
- **WHEN** a POST tool is called
- **THEN** the request carries the session cookie and the matching CSRF header

#### Scenario: Expired session
- **WHEN** the API responds 401
- **THEN** the tool returns `Forbidden` with the message "session expired, refresh the session cookie" and does not retry

### Requirement: Run start and status are separate
Starting a portfolio run SHALL return the run id from the `202` response immediately, and polling SHALL be a separate tool; the server MUST NOT block waiting for completion.

#### Scenario: Start then poll
- **WHEN** `portfolio_run_start` succeeds and `portfolio_run_status` is later called with the id
- **THEN** the second call returns the current state, including a failed state reported for a dead worker

#### Scenario: Active run already exists
- **WHEN** the API rejects a start because the organisation already has an active run
- **THEN** the tool returns a typed conflict result that includes the existing run id

### Requirement: Idempotent writes
POST tools that the API supports idempotency for SHALL send an idempotency key, and a retry of the same logical call SHALL reuse the same key.

#### Scenario: Retried call
- **WHEN** the same tool call is repeated after a timeout
- **THEN** both requests carry the same idempotency key

### Requirement: Backoff on limits
On HTTP 429 or a quota error the tool SHALL return `RateLimited` or `QuotaExceeded` with the retry time and SHALL NOT retry on its own.

#### Scenario: Quota exhausted
- **WHEN** the API reports plan quota exhausted
- **THEN** the tool returns `QuotaExceeded` and makes no further request

### Requirement: Bounded result size
List-style tools SHALL accept a limit, SHALL cap it at a documented maximum, and SHALL state how many rows were omitted.

#### Scenario: Large portfolio
- **WHEN** a portfolio has more loans than the cap
- **THEN** the result contains the capped rows and an explicit "N more rows" count

### Requirement: Resources and prompt
The server SHALL publish the model card and scenario catalogue as resources and a prompt for explaining a loan decision.

#### Scenario: Read model card
- **WHEN** a client reads the model card resource
- **THEN** it receives the card currently served by the API, including its checksum

### Requirement: API drift detection
A contract test SHALL compare each tool's request and response schema to the API's actual schema (generated OpenAPI when available, otherwise controller-derived fixtures) and SHALL fail when an endpoint's path, method, required fields or response fields change without a matching tool update.

#### Scenario: Endpoint changed
- **WHEN** a controller adds a required request field that the tool schema lacks
- **THEN** the drift test fails and names the tool and field

#### Scenario: Endpoint removed
- **WHEN** an endpoint a tool maps to no longer exists
- **THEN** the drift test fails and names the tool
