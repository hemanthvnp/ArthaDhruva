## ADDED Requirements

### Requirement: Job model mirrors CI
The server SHALL define one job model covering every job and step in `.github/workflows/ci.yml` and `security.yml`, and a test SHALL fail if a step in those workflows is missing from the model.

#### Scenario: New CI step added
- **WHEN** a step is added to `ci.yml` without updating the model
- **THEN** the mirror test fails and names the missing step

### Requirement: Asynchronous preflight
`preflight` SHALL start the checks and return a run id immediately, `preflight_status` SHALL report progress, an ETA, per-step results and a verdict, and `preflight_cancel` SHALL stop the run and its child processes.

#### Scenario: Long run
- **WHEN** a preflight including the Maven tests is started
- **THEN** the call returns a run id at once and status reports the current step and elapsed time

### Requirement: Honest outcomes
Each step SHALL end as passed, failed or skipped with a reason, and a step needing Docker or a missing tool MUST be reported as skipped, never as passed.

#### Scenario: Docker down
- **WHEN** Docker is not running and a promtool step is reached
- **THEN** the step is skipped with reason "Docker is not running" and the verdict says PASS with skipped checks

### Requirement: Default is the full check
Preflight SHALL default to running every CI job, SHALL offer a mode limited to jobs whose paths the diff touches, and SHALL skip the k6 load test by default with the reason stated.

#### Scenario: Default run
- **WHEN** preflight is started with no options
- **THEN** all non-load-test jobs are in the plan and the load test is listed as skipped with its reason

### Requirement: Fast jobs first
Preflight SHALL run the cheapest jobs before the Maven tests so failures surface early.

#### Scenario: Ordering
- **WHEN** a full preflight plan is built
- **THEN** secret scan, deployment-file checks and frontend checks precede the backend tests

### Requirement: Static platform-drift checks
The server SHALL detect CRLF line endings in shell scripts and `mvnw`, `package.json` changed without the lockfile, case-only path collisions, committed secrets paths, and a local Node major version differing from CI.

#### Scenario: CRLF script
- **WHEN** a changed `.sh` file contains CRLF line endings
- **THEN** `static_checks` reports a high-severity finding naming the file

### Requirement: Typical CI time from history
`typical_time` SHALL report per-job median and p90 durations from successful historical runs and the longest chain as the expected total, labelling any job with fewer than three successful runs as a fallback guess, and SHALL NOT output failure probabilities.

#### Scenario: Sparse history
- **WHEN** a job has two successful runs in history
- **THEN** its duration is labelled "fallback guess"

### Requirement: Never pushes or edits
The server MUST NOT push, commit, tag or edit tracked files, and MUST write only under `.git/ci-guard`.

#### Scenario: Preflight side effects
- **WHEN** a preflight completes
- **THEN** the working tree and refs are unchanged and only `.git/ci-guard` files were written
