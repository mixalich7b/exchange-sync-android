## Purpose

Defines safe on-device diagnostic records that let a developer use ADB to trace connection, TLS/mTLS, ActiveSync, event-validation, Calendar Provider, worker, and synchronization failures to their technical cause without adding remote telemetry or persisting sensitive payloads.

## ADDED Requirements

### Requirement: ADB-readable on-device diagnostics
The application SHALL emit diagnostic records to Android's on-device system log under one documented stable application tag. Failure records SHALL remain readable through `adb logcat` while retained by the device log buffer, and the application SHALL NOT upload, separately persist, or display a diagnostic history.

#### Scenario: Developer collects current diagnostics
- **WHEN** a developer connects ADB after reproducing a failure and filters Logcat by the documented application tag
- **THEN** the retained records for that application process can be read without enabling an in-app diagnostic mode or contacting an external service

#### Scenario: Device log buffer rotates
- **WHEN** Android removes old records from its system log buffer or the buffer is cleared
- **THEN** the application has no separate diagnostic archive from which those records can be recovered

### Requirement: Correlated connection and TLS diagnostics
For every failed connection Save or manual recheck, the application SHALL log the operation kind, a process-local correlation identifier, the failed stage, the stable user-facing failure category, the request method and command when applicable, the destination host and path without query parameters, and the concrete exception class with a bounded, cycle-safe cause chain when an exception exists. TLS records SHALL distinguish local trust-anchor loading, server-chain validation, hostname verification, client-key and certificate-chain resolution, TLS handshake, and verification that the selected client certificate participated in mTLS.

#### Scenario: TCP or network request fails
- **WHEN** DNS resolution, routing, TCP connection, timeout, reset, cancellation-independent I/O, or HTTP response processing fails during a connection operation
- **THEN** one correlated failure record identifies the network stage, concrete exception and cause classes, sanitized messages, destination host and path, and resulting connection failure category

#### Scenario: Server TLS validation fails
- **WHEN** local CA loading, server certificate-path validation, certificate validity, or hostname verification fails
- **THEN** correlated records identify the failing server-validation stage, available non-secret certificate metadata or local-anchor filename, concrete exception cause chain, and mapped TLS category

#### Scenario: Client mTLS fails
- **WHEN** the selected alias lacks usable key material, the handshake rejects the client identity, the peer closes during handshake, or the completed handshake does not show the selected client certificate
- **THEN** correlated records identify the client-authentication stage, whether key and chain resolution succeeded, available public-certificate fingerprint metadata, concrete exception cause chain, and mapped client-certificate category

#### Scenario: HTTP or capability validation fails
- **WHEN** redirect validation, HTTP status handling, ActiveSync header parsing, version negotiation, or required-command validation fails
- **THEN** correlated records identify the sanitized endpoint, status or validation reason, advertised protocol and command names when safe, and resulting failure category

### Requirement: Correlated calendar and synchronization diagnostics
The application SHALL log calendar synchronization failures with the synchronization generation, run token, trigger, current phase, transient-attempt count, ActiveSync command or local operation, stable problem category, and terminal outcome when those values are available. Unexpected exceptions SHALL include their concrete class and bounded, cycle-safe cause chain before they are converted into retry or blocked outcomes.

#### Scenario: Remote calendar request fails
- **WHEN** capability discovery, `FolderSync`, or `Sync` fails during a synchronization run
- **THEN** the diagnostic record correlates the command failure with its generation, run token, phase, sanitized endpoint, HTTP or protocol outcome, retry classification, and exception cause chain when present

#### Scenario: Local calendar operation fails
- **WHEN** calendar discovery, planning, provider query, provider batch application, cleanup, or checkpoint commit fails
- **THEN** the diagnostic record identifies the safe local operation, synchronization correlation fields, concrete failure cause, resulting problem category, and whether the run retries, blocks, or becomes obsolete

#### Scenario: WorkManager execution ends abnormally
- **WHEN** a periodic trigger or synchronization worker receives invalid input, throws unexpectedly, requests retry, blocks, is cancelled, or completes
- **THEN** a diagnostic record identifies the worker kind, safe input correlation fields, and resulting worker or synchronization outcome

### Requirement: Event-validation diagnostics
When an ActiveSync calendar command or mapped Calendar Provider event is rejected as malformed or unrepresentable, the application SHALL log the command kind, opaque ActiveSync `ServerId` when available, the validation or representation rule that failed, the synchronization correlation fields, and the exception class and cause chain. It SHALL NOT log the event subject, body, location, attendees, organizer address, raw timestamps collection, or raw WBXML/event payload.

#### Scenario: ActiveSync event data is invalid
- **WHEN** an Add or Change command fails required-field, time-range, all-day, recurrence, attendee, meeting-response, timezone, or value parsing validation
- **THEN** the diagnostic record identifies the command kind, available `ServerId`, failed validation rule, and synchronization run without including personal event content or raw application data

#### Scenario: Event cannot be represented locally
- **WHEN** a parsed event or exception cannot be represented by Android Calendar Provider planning or application
- **THEN** the diagnostic record identifies the safe planning or provider rule, available `ServerId`, synchronization run, and resulting calendar problem without including personal event content

### Requirement: Diagnostic secret and personal-data boundary
Diagnostic records SHALL NOT contain cookie names or values, `Cookie` or `Set-Cookie` headers, authorization headers, email addresses, `domain\\login`, certificate aliases, private keys, raw certificate encodings, full URLs or query strings, request or response bodies, raw WBXML, event subject/body/location/attendees/organizer, or exported event payloads. Exception messages and protocol values SHALL be sanitized before emission, and diagnostic logging failure SHALL NOT change connection or synchronization behavior.

#### Scenario: Failure contains sensitive request context
- **WHEN** an exception message or HTTP object contains a query-bearing URL, account identifier, cookie, authorization data, or protocol body
- **THEN** the emitted record includes only allow-listed diagnostic fields and sanitized text, with the sensitive value absent

#### Scenario: Throwable graph is malformed or logging fails
- **WHEN** an exception cause chain is cyclic or deeper than the documented bound, or the device logging call itself cannot complete
- **THEN** formatting terminates safely, notes truncation when possible, and preserves the original connection or synchronization outcome

