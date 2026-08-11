## MODIFIED Requirements

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

## ADDED Requirements

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
