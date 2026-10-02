## ADDED Requirements

### Requirement: Model cards exported with the artifact
The system SHALL export, with every validated model artifact, a model card holding at minimum: the model's version, when and on what data window it was trained, its algorithm and hyperparameters, its validation results in time and out of time, its known limitations, and the SHA-256 of the artifact it describes.

#### Scenario: Exporting a model writes its card
- **WHEN** an export script (`export_model.py`, `export_survival_model.py`) finishes writing an ONNX artifact
- **THEN** it writes the model card next to it, including the checksum of the bytes just written

### Requirement: The running model matches its card
The system SHALL verify at startup that each artifact it loads is the one its card describes, and SHALL NOT serve requests with an artifact that fails that check.

#### Scenario: Artifact matches its card
- **WHEN** the service starts and the SHA-256 of a loaded artifact equals the checksum in its card
- **THEN** the model is registered with its version and marked as checksum-verified

#### Scenario: Artifact does not match its card
- **WHEN** the service starts and the SHA-256 of a loaded artifact differs from the checksum in its card
- **THEN** startup fails with an error naming the artifact, and no request is served

### Requirement: A complete model inventory
The system SHALL expose an inventory of every model it runs, validated or not, with each model's purpose, tier, version, artifact checksum and validation status.

#### Scenario: Listing the inventory
- **WHEN** an analyst requests `GET /v1/models`
- **THEN** the response lists every model the service runs, including those without a card

#### Scenario: A model without a card
- **WHEN** a model has no card or no out-of-time validation
- **THEN** it appears in the inventory marked as not validated, with its artifact checksum and a list of what is missing

#### Scenario: Reading a model card
- **WHEN** an analyst requests `GET /v1/models/{id}` for a model that has a card
- **THEN** the full card is returned; for an unknown id, or a model without a card, the response is 404

### Requirement: Model identity is observable per replica
The system SHALL export, for each model on each replica, a metric carrying the model's id, version and artifact checksum.

#### Scenario: Replicas serving different artifacts
- **WHEN** two replicas report different checksums for the same model for longer than a rolling deploy takes
- **THEN** the alert `ReplicasServeDifferentModels` fires
