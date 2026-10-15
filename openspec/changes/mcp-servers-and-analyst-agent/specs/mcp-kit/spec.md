## ADDED Requirements

### Requirement: Uniform result envelope
Every tool SHALL return both human-readable text and `structuredContent`, and SHALL return errors as MCP error results carrying a typed code, never as a thrown stack trace.

#### Scenario: Successful call
- **WHEN** a tool completes
- **THEN** the result contains text and a `structuredContent` object that validates against the tool's output schema

#### Scenario: Upstream failure
- **WHEN** the backing system is unreachable
- **THEN** the result is an error with code `UpstreamDown` and an actionable message, with no stack trace or internal hostnames

### Requirement: Typed error vocabulary
The kit SHALL define the error codes `NotFound`, `Forbidden`, `UpstreamDown`, `Timeout`, `RateLimited`, `QuotaExceeded` and `InvalidInput`, and `RateLimited` SHALL carry a retry-after value when the upstream provides one.

#### Scenario: Rate limited
- **WHEN** an upstream call returns HTTP 429 with a `Retry-After` header
- **THEN** the tool returns `RateLimited` with that retry-after value and does not retry automatically

### Requirement: Validated environment-only configuration
Configuration SHALL be read only from environment variables and validated at startup, and a server SHALL exit with a clear message naming the missing variable, without printing any secret value.

#### Scenario: Missing variable
- **WHEN** a required variable is absent at startup
- **THEN** the process exits non-zero and the message names the variable but contains no secret

### Requirement: Output redaction
The kit SHALL redact session cookies, bearer tokens, API keys and configured secret values from every outbound tool payload and log line.

#### Scenario: Secret in upstream error
- **WHEN** an upstream error body contains the configured session cookie value
- **THEN** the value is replaced by a redaction marker in the tool result and in logs

### Requirement: Subprocess allowlist
The subprocess runner SHALL refuse any command not on the calling server's allowlist before spawning, SHALL enforce a timeout, and SHALL terminate the process tree on timeout or cancel.

#### Scenario: Disallowed command
- **WHEN** a handler asks to run a command not on its allowlist
- **THEN** the runner refuses without spawning and returns `Forbidden`

### Requirement: Read-only guard
The kit SHALL provide a guard that marks a server read-only and causes any adapter method flagged as mutating to fail with `Forbidden`.

#### Scenario: Mutation in a read-only server
- **WHEN** a mutating adapter method is invoked in a read-only server
- **THEN** it fails with `Forbidden` and performs no I/O

### Requirement: Call logging without payloads
Each tool call SHALL log one line to stderr containing tool name, duration and outcome, and SHALL NOT write to stdout or log request or response payloads.

#### Scenario: Log content
- **WHEN** a tool call finishes
- **THEN** exactly one stderr line records name, duration and outcome, and nothing is written to stdout outside the protocol

### Requirement: In-memory test harness
The kit SHALL provide a harness that connects an MCP client to a server in-process so every tool can be called through the real protocol without a subprocess.

#### Scenario: Protocol test
- **WHEN** a test calls a tool through the harness
- **THEN** the call passes through MCP request and response validation as it would over stdio

### Requirement: Tool annotations
Every tool SHALL declare MCP annotations for `readOnlyHint`, `destructiveHint` and `idempotentHint`, and the kit SHALL fail registration of a tool that omits them or declares `readOnlyHint` true on a tool flagged mutating.

#### Scenario: Missing annotation
- **WHEN** a tool is registered without annotations
- **THEN** registration fails with an error naming the tool

#### Scenario: Read-only consistency
- **WHEN** a tool uses a mutating adapter method but declares `readOnlyHint` true
- **THEN** registration fails

### Requirement: Progress and cancellation
Long-running tools SHALL emit MCP progress notifications when the client supplies a progress token, and SHALL honour MCP cancellation by stopping work and any child processes.

#### Scenario: Progress during preflight
- **WHEN** a client calls a long-running tool with a progress token
- **THEN** it receives progress notifications with the current step and a monotonic progress value

#### Scenario: Client cancels
- **WHEN** the client sends a cancellation for an in-flight call
- **THEN** the server stops the work, terminates child processes, and returns without further side effects
