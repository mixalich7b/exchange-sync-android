## Context

See `proposal.md` for motivation and the delta specs for observable behavior. The current version negotiator already accepts ActiveSync 14.0, 14.1, 16.0, and 16.1, but each connection, capability, or command operation builds an independent OkHttp client with the default no-cookie policy. Persisted protocol checkpoints can also bypass `OPTIONS` after process recreation, so merely adding an in-memory cookie jar would not recreate a server session before the first resumed command.

Failure boundaries currently discard useful causes: KeyChain resolution collapses exceptions to unavailable, connection and command clients map exceptions to stable categories, protocol parsing maps several validation failures to `PROTOCOL_DATA`, synchronization catches unexpected exceptions before retrying, and worker adapters have a final retry fallback. The user-facing categories must remain stable, but diagnostics need to observe the original cause before each reduction.

The implementation must preserve the existing module direction, keep `:core` Android-free, use only local unit tests, and avoid exposing private endpoint queries, account data, certificate aliases, cookies, or calendar payloads.

## Goals / Non-Goals

**Goals:**

- Prove that Exchange product build family 15.2 interoperates through the existing supported ActiveSync protocol values.
- Give all ActiveSync clients for an exact profile one process-local, RFC-style cookie state without changing TLS or redirect validation.
- Rebootstrap capability and cookie state safely after process recreation.
- Preserve original failure causes long enough to emit correlated, sanitized Logcat diagnostics at every lossy boundary.
- Keep diagnostic event construction test-independent and non-fatal as requested; verify actual records manually through ADB.

**Non-Goals:**

- Detecting or branching on a server product build header; compatibility remains capability-driven.
- Durable cookie storage, cross-profile cookie sharing, or a general-purpose browser cookie store.
- Raw HTTP logging, OkHttp's body/header logging interceptor, remote telemetry, or an in-app diagnostics database.
- Changing user-facing failure categories or retry policy solely to make logs more detailed.

## Decisions

### 1. Treat 15.2 as a product build family, not a wire-protocol version

Microsoft identifies Exchange Server 2019 builds with `15.2.x`, while the ActiveSync `MS-ASProtocolVersions` values remain 14.0, 14.1, 16.0, and 16.1. The implementation will therefore retain the current `ActiveSyncVersion` enum and highest-mutual-version negotiation. Regression fixtures will model an Exchange 2019/15.2 `OPTIONS` response and prove that supported advertised EAS values succeed while a header containing only `15.2` remains incompatible.

The distinction follows Microsoft's [Exchange build-number table](https://learn.microsoft.com/en-us/exchange/new-features/build-numbers-and-release-dates) and [`MS-ASProtocolVersions` protocol specification](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ashttp/bc92056f-5c48-4775-9f0d-b16b86998e55).

Alternative considered: add `V15_2` to `ActiveSyncVersion`. Rejected because it would send a non-standard protocol value and could hide the actual compatibility problem.

### 2. Introduce one shared ActiveSync process runtime

`:infrastructure` will expose a small Android ActiveSync runtime/factory owned once by `AppContainer`. It will create both the connection verifier and remote calendar using the same credential access, local trust source, diagnostic sink, and profile-session registry. This preserves manual composition and avoids a dependency-injection framework.

The transport factory will receive the `ConnectionProfile` as well as the resolved client credential. It can then bind every newly built TLS/OkHttp client to the correct profile session while keeping the fixed client key manager and combined server trust unchanged.

Alternative considered: leave the two Android adapters self-contained and use a global static cookie jar. Rejected because global state would make tests and lifecycle ownership opaque and would make profile isolation harder to enforce.

### 3. Store cookies in a bounded, thread-safe profile-session registry

The runtime will own a bounded least-recently-used registry keyed by the exact normalized profile identity (server host, email, account, and Android certificate alias). Each entry contains:

- a thread-safe in-memory cookie jar;
- the last capability result established in this process, including terminal HTTPS endpoint and negotiated protocol version;
- a process-local correlation sequence, never persisted or displayed.

The cookie jar will use OkHttp's parsed cookie model and matching rules. On save it replaces cookies with the same name/domain/path identity, removes expired cookies, returns only `Cookie.matches(requestUrl)` values, and never exposes raw cookie state to diagnostics. Standard host/domain, path, Secure, expiry, and redirect-destination rules therefore continue to apply even though the app manually handles redirects. Multiple temporary draft profiles may coexist briefly; the small LRU bound prevents unbounded growth, and eviction only causes a future capability rebootstrap.

The successful connection probe and synchronization capability discovery both record terminal endpoint and protocol version in the same exact-profile entry. Calendar commands reuse this live result and cookie state. A different profile key never sees the prior entry's cookies.

Alternative considered: add the OkHttp URLConnection cookie adapter or another cookie dependency. Rejected because the existing OkHttp cookie primitives are sufficient and a small process-local implementation is easier to constrain and test.

Alternative considered: persist cookies in DataStore to avoid another `OPTIONS` after process death. Rejected because cookies are bearer-like server session data, no durable lifetime was requested, and process-local rebootstrap is safer.

### 4. Rebootstrap capabilities after process recreation

The remote calendar will use a live capability result only when it exists in the process-local profile session. Persisted endpoint and protocol checkpoints alone will no longer suppress `OPTIONS` in a cold process. Fresh discovery first repopulates cookie state and then chooses a protocol as follows:

1. Reuse the persisted protocol version when the fresh response still advertises it, preserving compatible synchronization keys.
2. Otherwise choose the highest supported advertised version and request the existing full-reset path before using protocol-dependent checkpoints.

Within one live process, successful capability state and cookies survive newly constructed TLS clients, command calls, retries, continuation slices, and connection rechecks. Expired cookies are pruned on every save/load operation.

Alternative considered: always repeat `OPTIONS` before every page. Rejected because it adds unnecessary traffic and still does not define sharing between the connection verifier and commands.

### 5. Emit allow-listed structured events through one Logcat sink

The runtime will own a `DeviceDiagnosticSink` implemented with Android's logging API and the stable tag `ExchangeSync`. Call sites create structured events rather than free-form log strings. A record contains only allow-listed fields such as component, stage, operation kind, process-local operation ID, generation, run token, trigger, phase, command, host, path, HTTP status, protocol version, retry attempt, safe reason code, mapped failure/problem, and outcome.

Severity is consistent:

- `INFO` for operation/session start, negotiated capability, phase transitions, and terminal success/cancellation/obsolete outcomes;
- `WARN` for rejected redirects, HTTP/protocol validation failures, invalid events, recoverable failures, and retries;
- `ERROR` for TLS/mTLS failures, critical local failures, and unexpected exceptions.

The sink catches its own failures so logging cannot change connection, calendar, or worker behavior. Android retains records only in its system log buffers; the app adds no storage or upload path.

Alternative considered: OkHttp's logging interceptor. Rejected because it is request/response oriented, cannot explain higher-level validation or sync state transitions, and creates an unacceptable risk of logging query account values, cookies, or WBXML bodies.

### 6. Keep `:core` Android-free with a narrow synchronization diagnostics port

Network, TLS, ActiveSync, parser, provider, and worker components in `:infrastructure` can write structured events directly. `ExecuteSynchronizationSlice` is the one important lossy boundary in `:core`, so it will receive a pure Kotlin `SyncDiagnosticsPort`. The port accepts typed synchronization context and outcome/failure events plus an optional `Throwable`; the Android implementation delegates to the same Logcat sink. Production composition supplies it, while a no-op default or test fake keeps existing core tests focused on behavior.

The core use case logs the original unexpected exception before recovery, remote/local classified failures, attempt increments, block reasons, full-reset and window-size recovery, phase changes, and terminal outcomes. Worker adapters add their input/result boundary so a failure can be followed from WorkManager through the same generation and run token.

Alternative considered: rethrow all core exceptions and log only in WorkManager. Rejected because it would bypass the existing checkpoint-aware recovery behavior and lose the phase and classification context available inside the use case.

### 7. Instrument lossy boundaries before classification

Diagnostics will be emitted at these boundaries:

- local CA listing/parsing and combined trust-manager construction;
- KeyChain private-key and certificate-chain resolution, without emitting the alias;
- OkHttp DNS/connect/secure-connect/response/failure callbacks and bounded body reading;
- connection probe, redirect, capability header evaluation, and selected-client-certificate handshake verification;
- capability and command clients before exception-to-category mapping;
- WBXML, FolderSync, Sync, and calendar application-data parsing;
- event mapping/planning and Calendar Provider query/batch/cleanup failures;
- synchronization recovery, retry/block/obsolete/complete transitions;
- periodic and execution worker input and result mapping.

Event parsing will preserve a stable validation reason code and the enclosing command's kind and opaque `ServerId` before converting the error to `PROTOCOL_DATA`. Provider planning will preserve the available event sync ID. Subject, body, location, organizer, attendees, and payload bytes never become diagnostic fields.

### 8. Format throwable data with a strict safety boundary

The sink will not pass a raw `Throwable` to Android logging because platform exception messages can contain a full query-bearing URL. A formatter will walk a cycle-safe bounded graph of causes and suppressed failures, record exception class names, sanitize and length-limit messages, and include a bounded number of stack-frame class/method/file/line entries. Known URL text is reduced to HTTPS host and path; query and user-info are dropped. Header-like cookie/authorization fragments, email/account patterns, certificate aliases, and control characters are removed.

Certificate diagnostics may include public subject/issuer summaries, validity, and SHA-256 fingerprints already allowed by the connection diagnostics contract, but never DER/PEM bytes or private-key data. Client-certificate logging is limited to chain length, key algorithm, and public leaf fingerprint; the KeyChain alias is excluded.

No unit tests will be added solely for log emission or formatting, per the requested scope. Manual ADB verification will exercise representative network, TLS/mTLS, event-validation, and sync failures and inspect the output for both diagnostic value and forbidden data.

## Risks / Trade-offs

- **[Cookie state disappears on process death]** → cold synchronization performs fresh capability discovery before commands, then reuses the new session.
- **[A server changes supported protocol while checkpoints exist]** → retain the checkpoint version only if still advertised; otherwise enter the existing full-reset recovery before using the replacement version.
- **[Concurrent connection check and background sync touch one session]** → use thread-safe jar/registry operations and isolate entries by exact profile identity; normal HTTP cookie replacement semantics define the winner.
- **[Temporary draft sessions grow]** → use a small bounded LRU registry; eviction is safe because capability discovery recreates state.
- **[Diagnostic text leaks private data]** → build records from allow-listed typed fields, never log request/response objects or headers, sanitize platform exception text, and manually inspect representative ADB output.
- **[Extra diagnostics become noisy]** → use one stable tag, consistent severity, correlation identifiers, and log phase transitions rather than every low-level byte or provider row.
- **[No automated logging tests allow a future regression]** → keep redaction centralized and the event API typed; the implementation task includes a manual forbidden-data inspection exactly as requested.

## Migration Plan

No persisted schema migration is required. Existing profiles and synchronization checkpoints remain readable. On the first synchronization after installing the change, the process-local session is empty, so the app performs `OPTIONS`, establishes current capability/cookie state, and then resumes compatible checkpoints or requests a full reset if the negotiated protocol changed.

Rollback removes the process runtime and diagnostics wiring. Persisted profiles and checkpoints remain compatible because no cookies, diagnostic events, or new fields are written to DataStore.
