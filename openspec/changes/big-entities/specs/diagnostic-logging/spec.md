## ADDED Requirements

### Requirement: Privacy-safe large-entity and provider sub-batch diagnostics
The application SHALL emit correlated aggregate diagnostics when attendee materialization is suppressed and while a Calendar Provider page is applied in bounded sub-batches. These records SHALL contain only bounded counts, limits, ordinals, enumerated outcomes, and existing safe synchronization correlation fields; they SHALL NOT contain attendee or organizer identities, event content, provider row identifiers, synchronization keys, or raw payloads. A failed sub-batch SHALL distinguish operations confirmed by earlier successful sub-batches from the unknown outcome of the failed provider call.

#### Scenario: Oversized attendee list is suppressed
- **WHEN** an event or recurrence exception has more than 100 effective attendee entries
- **THEN** a correlated diagnostic identifies the attendee limit, bounded input count, organizer-only representation, and number of omitted attendee rows without logging any attendee or organizer value

#### Scenario: Provider page is split into sub-batches
- **WHEN** a planned Calendar Provider page contains more than 50 operations
- **THEN** correlated diagnostics identify the bounded total operation count, total sub-batch count, each sub-batch ordinal and operation count, and cumulative confirmed applied-operation count

#### Scenario: Provider sub-batch fails after earlier success
- **WHEN** at least one provider sub-batch succeeds and a later sub-batch fails or has an ambiguous Binder outcome
- **THEN** the failure record identifies the failing sub-batch ordinal, its attempted operation count, the cumulative operations confirmed by earlier successes, the concrete sanitized failure cause, and the resulting retry or blocked outcome without claiming that the failed sub-batch applied zero operations

#### Scenario: Provider page completes through one sub-batch
- **WHEN** a planned page contains 50 or fewer operations and its single provider sub-batch succeeds
- **THEN** the provider summary reports one sub-batch and matching attempted and confirmed applied-operation counts
