## ADDED Requirements

### Requirement: Checksum check against the model card
The server SHALL compute checksums of the committed model artifacts and compare them to the checksums recorded in the model card, reporting each artifact as match, mismatch or missing.

#### Scenario: Artifact tampered
- **WHEN** an artifact's checksum differs from the model card
- **THEN** the result lists that artifact as mismatch with both checksums and an overall failure

### Requirement: Golden test runner limited to one class
The server SHALL run only `TermStructureGoldenTest` through Maven, with a timeout, and SHALL report passed, failed or skipped with the relevant output on failure.

#### Scenario: Golden test passes
- **WHEN** `run_golden_test` is called and Maven exits 0
- **THEN** the result is passed and includes the duration

#### Scenario: Maven unavailable
- **WHEN** the Maven wrapper cannot run
- **THEN** the result is skipped with the reason, never passed

### Requirement: Forbidden commands
The server MUST NOT run `export_*.py`, `fetch_macro.py`, `build_survival_dataset.py`, `golden_term_structure.py`, `python -m pipeline.run`, or any notebook or pipeline compose profile.

#### Scenario: Export requested
- **WHEN** any input asks for a model export or the pipeline
- **THEN** the server refuses with `Forbidden` and explains that the owner must run it explicitly

### Requirement: Re-run advisory
When model artifacts or export scripts have changed relative to the golden fixtures, the result SHALL state that the golden script must be re-run by the owner and SHALL NOT run it.

#### Scenario: Export script changed
- **WHEN** `backend/export_survival_model.py` differs from the base ref
- **THEN** the report includes the advisory that `golden_term_structure.py` must be re-run

### Requirement: Drift and backtest reports
The server SHALL fetch drift and survival-backtest summaries from the API and present them with the metric names and thresholds the API returns.

#### Scenario: Drift report
- **WHEN** `drift_report` is called
- **THEN** it returns the population-stability figures from `/models/pd_24m/drift` unaltered, with their source and timestamp
