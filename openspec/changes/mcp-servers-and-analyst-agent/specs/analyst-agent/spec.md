## ADDED Requirements

### Requirement: Budgeted tool-use loop
The agent SHALL stop a question when any configured limit on steps, tokens, wall-clock time or estimated cost is reached, and SHALL return the partial findings together with the limit that stopped it.

#### Scenario: Step limit
- **WHEN** a question needs more tool calls than the step limit
- **THEN** the agent stops, says the step limit was reached and returns what it verified so far

### Requirement: Fixed tenant per session
The tenant SHALL be fixed when a session is created, and no tool or model output SHALL be able to change it.

#### Scenario: Model asks for another tenant
- **WHEN** the model proposes a tool call naming a different tenant
- **THEN** the call is rejected and the session tenant is used or the call fails

### Requirement: No side effects by default
The agent SHALL expose only read tools, and `portfolio_run_start` SHALL be available only when the session sets an explicit allow flag.

#### Scenario: Run start without the flag
- **WHEN** the model calls `portfolio_run_start` and the flag is unset
- **THEN** the call is refused and no request is made to the API

### Requirement: Citation contract
Every numeric figure in the final answer SHALL cite the id of the tool result it came from.

#### Scenario: Uncited figure
- **WHEN** the draft answer contains a number with no citation
- **THEN** the verifier treats it as unsupported

### Requirement: Grounding verifier
A verifier SHALL extract each cited figure and compare it to the stored tool result using the documented tolerance and unit rules, and SHALL allow one retry before returning "could not verify" with the offending figures.

#### Scenario: Figure mismatch
- **WHEN** the answer states a loss of 4.1% and the cited result says 3.4%
- **THEN** verification fails, the agent retries once, and if still wrong the response lists the mismatching figure

#### Scenario: Rounding within tolerance
- **WHEN** the answer states 3.4% and the result is 0.03412
- **THEN** verification passes

### Requirement: Injection handling
Tool output SHALL be provided to the model as fenced untrusted data, instruction-like text in tenant-written fields SHALL be neutralised first, and tool permissions SHALL NOT widen during a session.

#### Scenario: Injected instruction in an audit row
- **WHEN** an audit row contains text instructing the agent to call another tool
- **THEN** the agent makes no such call and the answer treats the text as data

### Requirement: Run trace
Each question SHALL write a local trace containing steps, tool calls, token use, cost and the verifier verdict, without credentials.

#### Scenario: Trace content
- **WHEN** a question completes
- **THEN** a trace file exists listing every tool call and the verdict, and contains no session cookie

### Requirement: Separate from the in-product assistant
The agent MUST NOT modify or depend on `/assistant/chat`.

#### Scenario: Assistant unchanged
- **WHEN** the agent is built and run
- **THEN** no file under `backend/` has changed
