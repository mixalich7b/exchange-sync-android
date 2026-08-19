# diagnostic-logging Specification

## Purpose

Defines safe on-device diagnostic records that let a developer use ADB to trace connection, TLS/mTLS, ActiveSync, event-validation, Calendar Provider, worker, and synchronization failures to their technical cause without adding remote telemetry or persisting sensitive payloads.

## Requirements

### Requirement: ADB-readable on-device diagnostics
The application SHALL emit diagnostic records to Android's on-device system log under one documented stable application tag. Failure records SHALL remain readable through `adb logcat` while retained by the device log buffer, and the application SHALL NOT upload, separately persist, or display a diagnostic history.

#### Scenario: Developer collects current diagnostics
- **WHEN** a developer connects ADB after reproducing a failure and filters Logcat by the documented application tag
- **THEN** the retained records for that application process can be read without enabling an in-app diagnostic mode or contacting an external service

#### Scenario: Device log buffer rotates
- **WHEN** Android removes old records from its system log buffer or the buffer is cleared
- **THEN** the application has no separate diagnostic archive from which those records can be recovered

### Requirement: Correlated connection and TLS diagnostics
For every failed connection Save or manual recheck, the application SHALL log the operation kind, a process-local correlation identifier, the failed stage, the stable user-facing failure category, the request method and command when applicable, the destination host and path without query parameters, and the concrete exception class with a bounded, cycle-safe cause chain when an exception exists. TLS records SHALL distinguish local trust-anchor loading, server-chain validation, hostname verification, client-key and certificate-chain resolution, TLS handshake, fixed-client-identity configuration, and whether client-certificate participation metadata is available from the platform. Diagnostics SHALL NOT convert absent local-certificate metadata into a connection failure.

#### Scenario: TCP or network request fails
- **WHEN** DNS resolution, routing, TCP connection, timeout, reset, cancellation-independent I/O, or HTTP response processing fails during a connection operation
- **THEN** one correlated failure record identifies the network stage, concrete exception and cause classes, sanitized messages, destination host and path, and resulting connection failure category

#### Scenario: Server TLS validation fails
- **WHEN** local CA loading, server certificate-path validation, certificate validity, or hostname verification fails
- **THEN** correlated records identify the failing server-validation stage, available non-secret certificate metadata or local-anchor filename, concrete exception cause chain, and mapped TLS category

#### Scenario: Client mTLS fails
- **WHEN** the selected alias lacks usable key material, fixed-identity TLS configuration fails, the handshake rejects client authentication, or the peer closes during handshake
- **THEN** correlated records identify the client-authentication stage, whether key and chain resolution succeeded, available public-certificate fingerprint metadata, concrete exception cause chain, and mapped client-certificate category

#### Scenario: Local-certificate metadata is unavailable after success
- **WHEN** fixed client identity configuration, TLS validation, and the terminal capability response succeed but the completed session exposes no local-certificate list
- **THEN** diagnostics record configured identity and unavailable participation evidence at informational severity without emitting a false client-certificate rejection

#### Scenario: HTTP or capability validation fails
- **WHEN** redirect validation, HTTP status handling, ActiveSync header parsing, version negotiation, or required-command validation fails
- **THEN** correlated records identify the sanitized endpoint, status or validation reason, advertised protocol and command names when safe, and resulting failure category

### Requirement: Correlated calendar and synchronization diagnostics
The application SHALL log calendar synchronization failures with the synchronization generation, run token, trigger, current phase, transient-attempt count, ActiveSync command or local operation, stable problem category, and terminal outcome when those values are available. Unexpected exceptions SHALL include their concrete class and bounded, cycle-safe cause chain before they are converted into retry or blocked outcomes. Cleanup failures SHALL be attributed to the cleanup and ownership boundary rather than appearing only as a generic synchronization exception.

#### Scenario: Remote calendar request fails
- **WHEN** capability discovery, `FolderSync`, or `Sync` fails during a synchronization run
- **THEN** the diagnostic record correlates the command failure with its generation, run token, phase, sanitized endpoint, HTTP or protocol outcome, retry classification, and exception cause chain when present

#### Scenario: Local calendar operation fails
- **WHEN** calendar discovery, planning, provider query, provider batch application, cleanup, or checkpoint commit fails
- **THEN** the diagnostic record identifies the safe local operation, synchronization correlation fields, concrete failure cause, resulting problem category, and whether the run retries, blocks, remains cleanup-pending, or becomes obsolete

#### Scenario: Provider throws an unexpected runtime failure during cleanup
- **WHEN** Calendar Provider rejects owned-calendar cleanup with an unexpected runtime exception
- **THEN** a correlated calendar cleanup record includes the exception class and bounded cause chain before the synchronization state records an actionable cleanup outcome

#### Scenario: WorkManager execution ends abnormally
- **WHEN** a periodic trigger or synchronization worker receives invalid input, throws unexpectedly, requests retry, blocks, is cancelled, or completes
- **THEN** a diagnostic record identifies the worker kind, safe input correlation fields, and resulting worker or synchronization outcome

### Requirement: Privacy-safe synchronization progress summaries
The application SHALL emit correlated summaries at the ActiveSync page and Calendar Provider boundaries so a developer can determine whether an apparently empty mirror originated at the server response, protocol decoder, event mapper, provider planner, provider batch, or checkpoint commit. Summaries SHALL contain only bounded counts, booleans, enumerated modes and outcomes, and existing safe correlation fields; they SHALL NOT contain synchronization-key values, raw WBXML, event fields, account identifiers, calendar payloads, or timestamp collections.

#### Scenario: Sync page is requested and decoded
- **WHEN** a priming, full, or incremental `Sync` request completes
- **THEN** correlated records identify the request mode, window size, empty-versus-WBXML response, bounded response size, command counts by safe command kind, `MoreAvailable` presence, and whether the synchronization key advanced without logging either key value

#### Scenario: Calendar page is planned and applied
- **WHEN** a decoded page reaches owned-calendar resolution, event planning, provider batching, and checkpoint commit
- **THEN** correlated records identify create, reuse, repair, or delete ownership outcome and bounded input, planned-operation, applied-operation, and checkpoint outcomes without logging event or account content

#### Scenario: Owned-calendar cleanup is attempted
- **WHEN** profile activation, reset, disable, startup reconciliation, or permission recovery queries and deletes application-owned calendars
- **THEN** correlated records identify the bounded owned-row count, cleanup trigger, delete success or failure, and durable cleanup outcome while omitting unrelated calendar identities and content

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

### Requirement: Adaptive page and folder-preparation diagnostics
The application SHALL emit privacy-safe correlated diagnostics that distinguish page-scaled capacity recovery from malformed WBXML and that identify whether primary-calendar folder preparation was refreshed or reused. Capacity records SHALL identify the bounded capacity kind, command, current window, recovery or terminal outcome, and reduced window when applicable. Folder-preparation records SHALL identify refresh, reuse, invalidation, and process-cold outcomes without logging folder names, collection identifiers, synchronization keys, profile identity, or protocol payloads.

#### Scenario: WBXML page capacity triggers recovery
- **WHEN** a Calendar `Sync` response exceeds the bounded WBXML document or element capacity at a window greater than one
- **THEN** correlated records identify a WBXML capacity outcome, the current and reduced bounded window values, unchanged-checkpoint recovery, and continuation without labeling the response as malformed protocol data

#### Scenario: WBXML syntax is malformed
- **WHEN** a response violates WBXML syntax, encoding, nesting, or protocol structure
- **THEN** correlated records identify the stable malformed-data validation reason and terminal protocol classification rather than a page-size recovery

#### Scenario: Capacity remains exceeded at window one
- **WHEN** the same remote or provider capacity remains exceeded at the minimum window
- **THEN** correlated records identify the capacity kind, minimum-window terminal outcome, and resulting safe problem category without logging the event or response content

#### Scenario: Prepared folder state is reused
- **WHEN** another page, adaptive retry, or continuation slice in the same logical run uses the process-local prepared folder state
- **THEN** correlated diagnostics record a bounded reuse outcome without a second successful `FolderSync` request record and without exposing the primary collection identifier or folder synchronization key

#### Scenario: Folder state is refreshed
- **WHEN** a new logical run, cold process, or invalidated folder state requires `FolderSync`
- **THEN** correlated diagnostics record the safe refresh reason and command outcome without exposing folder names, identifiers, or synchronization keys

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
