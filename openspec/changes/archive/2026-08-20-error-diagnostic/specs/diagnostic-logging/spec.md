## MODIFIED Requirements

### Requirement: Privacy-safe large-entity and provider sub-batch diagnostics
The application SHALL emit correlated aggregate diagnostics when attendee materialization is suppressed and while a Calendar Provider page is applied in bounded sub-batches. Successful sub-batch and attendee-suppression records SHALL contain only bounded counts, limits, ordinals, enumerated outcomes, and existing safe synchronization correlation fields. When a provider sub-batch fails, the application SHALL additionally emit a bounded structured record for every attempted operation in that sub-batch. Each operation record SHALL identify its global and sub-batch index, operation kind, target entity, safe row or back-reference identity, column presence, and every submitted provider value allowed by the diagnostic secret and personal-data boundary. Values SHALL be sanitized and individually length-bounded. Attendee and organizer operations SHALL expose only structural metadata and bounded counts, never attendee or organizer values. A failed sub-batch SHALL distinguish operations confirmed by earlier successful sub-batches from the unknown outcome of the failed provider call, and the detailed records SHALL NOT claim which individual operation failed when the platform does not provide that information.

#### Scenario: Oversized attendee list is suppressed
- **WHEN** an event or recurrence exception has more than 100 effective attendee entries
- **THEN** a correlated diagnostic identifies the attendee limit, bounded input count, organizer-only representation, and number of omitted attendee rows without logging any attendee or organizer value

#### Scenario: Provider page is split into sub-batches
- **WHEN** a planned Calendar Provider page contains more than 50 operations
- **THEN** correlated diagnostics identify the bounded total operation count, total sub-batch count, each sub-batch ordinal and operation count, and cumulative confirmed applied-operation count

#### Scenario: Provider sub-batch fails after earlier success
- **WHEN** at least one provider sub-batch succeeds and a later sub-batch fails or has an ambiguous Binder outcome
- **THEN** the aggregate failure record identifies the failing sub-batch ordinal, its attempted operation count, the cumulative operations confirmed by earlier successes, the concrete sanitized failure cause, and the resulting retry or blocked outcome without claiming that the failed sub-batch applied zero operations

#### Scenario: Failed provider call records attempted operations
- **WHEN** Calendar Provider rejects or ambiguously terminates a sub-batch containing event, exception, reminder, attendee, organizer, update, or delete operations
- **THEN** one correlated bounded detail record per attempted operation identifies its indexes, operation kind, target entity, reference metadata, column presence, and all permitted submitted values without claiming that any particular operation caused the failure

#### Scenario: Failed provider operation contains excluded content
- **WHEN** an attempted operation includes event title or description, organizer data, or attendee data
- **THEN** its diagnostic detail can identify the affected column or operation structurally but contains none of those submitted values

#### Scenario: Provider page completes through one sub-batch
- **WHEN** a planned page contains 50 or fewer operations and its single provider sub-batch succeeds
- **THEN** the provider summary reports one sub-batch and matching attempted and confirmed applied-operation counts without emitting provider-operation value details

### Requirement: Event-validation diagnostics
When an ActiveSync calendar command or mapped Calendar Provider event is rejected as malformed or unrepresentable, the application SHALL log the command kind, opaque ActiveSync `ServerId` when available, a stable validation or representation rule, synchronization correlation fields, and exception class and cause chain. The failure SHALL also emit a structured snapshot of every available event or exception field permitted by the diagnostic secret and personal-data boundary. This snapshot MAY include actual UID and protocol identifiers, location, timestamps, duration and time relationships, all-day state, timezone, recurrence, exception identity and non-narrative fields, meeting and response state, availability, sensitivity, reminders, field presence or source, provider row identity, and bounded collection counts. For a partial `Change`, the snapshot SHALL distinguish response values, prior provider values, and effective merged values when those states are available. Every value SHALL be sanitized and individually length-bounded. The snapshot SHALL NOT contain subject or title values, body or description values, attendee values, organizer values, raw WBXML, or an exported raw event payload.

#### Scenario: ActiveSync event data is invalid
- **WHEN** an Add or Change command fails required-field, time-range, all-day, recurrence, attendee, meeting-response, timezone, or value parsing validation
- **THEN** correlated diagnostics identify the command kind, available `ServerId`, failed validation rule, and all permitted parsed or failing-field values needed to reproduce the validation decision without including excluded content or raw application data

#### Scenario: Partial change conflicts with prior event time
- **WHEN** a partial Change fails because its response start or end combined with a prior provider snapshot produces an equal or reversed time range
- **THEN** the diagnostic snapshot identifies the response, prior, and effective time values and their relationship so the source of the invalid range is distinguishable

#### Scenario: Event cannot be represented locally
- **WHEN** a parsed event or exception cannot be represented by Android Calendar Provider planning or application
- **THEN** correlated diagnostics identify the stable planning or provider rule, available `ServerId`, synchronization run, resulting calendar problem, and permitted input and derived values without including excluded content

#### Scenario: Validation involves attendees or organizer
- **WHEN** an attendee, meeting-response, or organizer-related rule rejects an event
- **THEN** diagnostics identify the rule, relevant field presence, and bounded collection counts but contain no attendee or organizer value

### Requirement: Diagnostic secret and personal-data boundary
Diagnostic records SHALL NOT contain cookie names or values, `Cookie` or `Set-Cookie` headers, authorization headers, email addresses, `domain\\login`, certificate aliases, private keys, raw certificate encodings, full URLs or query strings, request or response bodies, raw WBXML, event or exception subject/title values, event or exception body/description values, attendee values, organizer values, or exported event payloads. A structured event-validation or failed-provider-operation record MAY contain every other available calendar field and provider value, including location, timestamps, recurrence and timezone data, identifiers, row references, status values, reminders, field presence, and bounded collection counts. A valid fixed-offset provider timezone such as `GMT+03:00` is timezone data rather than a URL and SHALL remain available after sanitization. These values and exception text SHALL be sanitized and individually length-bounded before emission. Detailed calendar values SHALL be emitted only for the corresponding failure and SHALL NOT be added to successful progress records. Diagnostic logging failure SHALL NOT change connection or synchronization behavior.

#### Scenario: Failure contains sensitive request context
- **WHEN** an exception message or HTTP object contains a query-bearing URL, account identifier, cookie, authorization data, or protocol body
- **THEN** the emitted record includes only allow-listed diagnostic fields and sanitized text, with the sensitive value absent

#### Scenario: Calendar failure contains allowed and excluded event fields
- **WHEN** an invalid event or failed provider operation contains timestamps, location, recurrence, subject, body, attendees, and organizer data
- **THEN** its failure diagnostics retain the sanitized and bounded timestamps, location, recurrence, and other permitted values while omitting subject, body, attendee, and organizer values

#### Scenario: Successful synchronization contains the same event
- **WHEN** the same calendar values are parsed, planned, and applied without a validation or provider failure
- **THEN** successful progress diagnostics remain aggregate and do not emit the detailed calendar snapshot or provider-operation values

#### Scenario: Throwable graph is malformed or logging fails
- **WHEN** an exception cause chain is cyclic or deeper than the documented bound, a permitted value exceeds its bound, or the device logging call itself cannot complete
- **THEN** formatting terminates safely, sanitizes or truncates the affected diagnostic when possible, and preserves the original connection or synchronization outcome
