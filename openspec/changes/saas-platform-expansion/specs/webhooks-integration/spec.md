## ADDED Requirements

### Requirement: Webhook subscription registration
An organization admin SHALL be able to register a webhook endpoint URL and select which event types it receives.

#### Scenario: Admin registers a webhook
- **WHEN** an admin registers a webhook URL subscribed to the "case flagged" event type
- **THEN** future case-flagged events for that organization are queued for delivery to that URL

### Requirement: At-least-once webhook delivery via transactional outbox
The system SHALL guarantee at-least-once delivery of webhook events by persisting each event to an outbox table in the same transaction as the triggering state change, before attempting HTTP delivery.

#### Scenario: Application crash after state change but before delivery
- **WHEN** the triggering state change commits but the application crashes before the webhook HTTP call is made
- **THEN** the outbox record for that event still exists after restart and is delivered on the next delivery attempt

#### Scenario: Webhook endpoint is temporarily unreachable
- **WHEN** a webhook delivery attempt fails due to a network error or non-2xx response
- **THEN** the system retries delivery with backoff up to a configured maximum attempt count, and marks the event as failed (not silently dropped) if all attempts are exhausted

### Requirement: Idempotent webhook payloads
Each webhook delivery SHALL include a unique event ID so a receiving system can deduplicate retried deliveries.

#### Scenario: Receiver deduplicates a retried delivery
- **WHEN** the same event is delivered more than once due to a retry
- **THEN** both deliveries carry an identical event ID, allowing the receiver to detect the duplicate

### Requirement: Core system batch integration endpoint
The system SHALL accept batch loan data submissions (e.g., from a core banking or loan origination system) via an authenticated API endpoint, distinct from interactive UI-driven entry.

#### Scenario: Batch submission is accepted
- **WHEN** an authenticated API-key request submits a batch of loan records in the supported format
- **THEN** each valid record is ingested into that organization's tenant-scoped data, and invalid records are reported individually without failing the entire batch
