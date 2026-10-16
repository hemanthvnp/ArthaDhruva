## ADDED Requirements

### Requirement: Read-only observability queries
The server SHALL query Prometheus for instant and range metrics and for alert state using fixed, bounded queries, and SHALL return values with their timestamps.

#### Scenario: Firing alert
- **WHEN** `alert_state` is called and an alert is firing
- **THEN** the result lists its name, labels and the time it began firing

### Requirement: Azure allowlist
The server SHALL run only a fixed set of read-only `az` subcommands for VM power state and SHALL refuse any other `az` invocation, including anything that creates, modifies, deletes, starts or stops resources.

#### Scenario: Mutating az command
- **WHEN** a request would run `az vm deallocate` or any non-allowlisted subcommand
- **THEN** it is refused with `Forbidden` before spawning

### Requirement: Deallocated VM is a normal state
When the VM is deallocated or unreachable because it is asleep, the server SHALL report that state plainly and SHALL NOT report an error or an unhealthy deployment.

#### Scenario: Idle VM
- **WHEN** the VM power state is deallocated
- **THEN** `health` returns state "asleep" with a hint that `wake` will start it, not an error

### Requirement: Wake is the only action
`wake` SHALL call only the configured public wake URL, SHALL be idempotent, and SHALL report whether the VM was already running, was starting, or was started by this call.

#### Scenario: Wake an asleep VM
- **WHEN** `wake` is called while the VM is deallocated
- **THEN** it calls the wake URL once and returns "starting" with a poll hint

### Requirement: Bounded retries
Network calls SHALL use short timeouts and at most a fixed number of retries with backoff.

#### Scenario: Prometheus down
- **WHEN** Prometheus does not respond
- **THEN** the tool returns `UpstreamDown` after the bounded retries, within the stated maximum time
