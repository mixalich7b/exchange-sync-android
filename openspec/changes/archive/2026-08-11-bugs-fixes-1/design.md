## Context

See [proposal.md](proposal.md) for the observed failures. The affected paths cross `:core`, `:feature:settings`, and `:infrastructure`, but the existing dependency direction and manual composition root remain sufficient.

The investigation established four concrete compatibility defects and one observability gap:

- Android/Conscrypt can complete the fixed-identity TLS exchange without exposing the selected client chain through OkHttp's `Handshake.localCertificates`.
- ActiveSync 16.x encodes `airsyncbase:InstanceId` as Compact DateTime (`yyyyMMdd'T'HHmmss'Z'`), while the current parser expects an extended fractional form.
- Android 16 Calendar Provider rejects a selection supplied to the item `Calendars/<id>` delete URI; the current cleanup combines that item URI with a selection and lets the resulting runtime exception escape its adapter boundary.
- Core transitions already permit a run request from blocked state, but the settings presentation enables Sync now only while idle.
- The supplied logs prove successful `FolderSync` and `Sync` HTTP exchanges, but do not contain safe command, mapping, provider-operation, or checkpoint counts. They therefore cannot distinguish a genuinely empty server page from a later zero-result stage.

The trust boundary remains HTTPS with normal platform hostname and server-chain validation plus packaged public CA anchors. KeyChain private keys remain outside application storage. Exchange calendar payloads and Android calendar contents remain sensitive and are excluded from diagnostics.

## Goals / Non-Goals

**Goals:**

- Repair each confirmed defect within the existing adapters and synchronization state machine.
- Preserve the all-history, no-date-filter initial synchronization contract while making each data-flow boundary diagnosable without personal data.
- Make cleanup and manual recovery idempotent across process restarts.
- Retain strict application-controlled redirect policy for both capability and command requests.

**Non-Goals:**

- Replacing the current synchronization architecture, changing the ActiveSync version-negotiation policy, or adding dependencies.
- Proving at the application layer which certificate bytes a TLS provider actually transmitted when the provider exposes no such evidence.
- Guessing that an unrelated OEM local calendar belongs to this application or removing it.
- Skipping malformed server commands and advancing the checkpoint, which could make the mirror permanently incomplete.
- Logging raw HTTP/WBXML, event fields, exact response timestamps, account values, certificate private material, or synchronization keys.

## Decisions

### 1. Accept the validated fixed-identity TLS result without relying on local-certificate metadata

The existing fixed-alias key manager remains the sole source of client key material. Connection setup must still resolve the selected KeyChain private key and chain, configure that identity, validate the peer chain and hostname, and receive a terminal successful ActiveSync capability response. The connection and command paths will stop treating an empty `Handshake.localCertificates` list as proof that authentication failed.

Diagnostics will separately state that the identity was configured and whether platform participation metadata was available. Handshake failures, peer rejection, missing KeyChain material, and authentication HTTP outcomes continue to fail normally. A successful response is operational evidence that the configured transport worked, but the application will not describe it as cryptographic proof that the provider transmitted a particular leaf certificate.

Alternatives considered:

- Comparing the selected leaf with `Handshake.localCertificates` was rejected because the supplied Android run demonstrates that this provider metadata is not portable.
- A second request without a client identity was rejected as a proof mechanism because an endpoint can permit anonymous `OPTIONS`, connection reuse complicates attribution, and the extra request changes server-visible behavior.
- Instrumenting lower-level TLS sockets or replacing OkHttp was rejected because it adds substantial security-sensitive machinery and still cannot reliably recover provider-internal evidence on every Android TLS implementation.

### 2. Retain `RedirectTracker` and keep OkHttp automatic redirects disabled

Both `OPTIONS` and ActiveSync command requests will continue through the existing explicit redirect loop. It preserves the original method and, for commands, the request body; accepts only HTTPS destinations without user information; rejects cycles and malformed locations; enforces the five-hop product limit; and emits one safe record per hop. Normal TLS, cookie-jar, and hostname rules apply independently at each destination.

OkHttp automatic redirects are not sufficient for this protocol policy: redirect handling permits method conversion for common 301/302/303 cases and uses a broader follow-up limit. Enabling it would also move cycle, downgrade, and per-hop decisions below the application's diagnostic boundary. The tracker is therefore retained rather than supplemented by automatic redirects.

### 3. Reuse the protocol Compact DateTime parser for `InstanceId`

`InstanceId` will use the same strict UTC Compact DateTime grammar already used for ActiveSync calendar values. The parser returns the original occurrence instant used by the existing exception mapper. Invalid, missing, or non-UTC values remain protocol-data failures; the page is not partially committed and its synchronization key is not advanced.

Accepting both the current incorrect extended form and the protocol form was rejected. Leniency would hide server-data errors and create an application-specific identity grammar that is not required by ActiveSync 16.0 or 16.1.

### 4. Preserve unfiltered full synchronization and instrument its existing stages

The initial flow remains `FolderSync`, a `SyncKey=0` priming request without `GetChanges`, followed by nonzero `GetChanges` pages without `FilterType`. Omitting `FilterType` already requests all objects regardless of age, so the change will not add an arbitrary window or a speculative alternate request.

Diagnostics will use typed enums, booleans, and bounded counts already accepted by the formatter allowlist. One correlated summary will be emitted at each material boundary:

1. request/response: priming, full, or incremental mode; configured window size; empty body versus WBXML; bounded byte count; HTTP/protocol outcome;
2. decoding: bounded Add, Change, Delete, and total command counts; `MoreAvailable`; whether the key advanced, never either key value;
3. mapping/planning: bounded input, accepted, rejected, and planned-operation counts;
4. provider/checkpoint: owned-calendar action, attempted and applied operation counts, and checkpoint outcome.

The diagnostic model and formatter remain centralized so newly added values cannot bypass sanitization. The response body, event content, account identity, collection identifier, provider row identifiers, timestamps, and key values are not logged.

Issuing a second differently filtered synchronization or treating an empty valid page as an error was rejected. The existing request is protocol-correct, and an empty page can legitimately mean the server has no pending objects.

### 5. Delete owned calendars through a collection URI with a complete ownership predicate

Cleanup will first resolve rows owned by the stable application account name, account type, and internal calendar name. Each deletion will target the Calendar Provider collection URI with sync-adapter account query parameters and a selection containing the provider `_id` plus the complete ownership tuple. This retains both Android's collection-URI transaction contract and the defense-in-depth ownership check.

The adapter will check the affected-row result. Provider access, security, and other provider-originated runtime failures will be mapped to the existing stable permission/provider problem boundary after a sanitized diagnostic is emitted. Coroutine cancellation remains cancellation and is not converted into a provider problem.

Using the item URI with a non-null selection was rejected because Android 16 rejects that combination. Using the item URI with no selection was also rejected because it drops the final provider-side ownership predicate. Deleting by account name alone or adopting an OEM `account_name_local` row was rejected because neither proves application ownership.

### 6. Make blocked runs and disabled cleanup independently retryable

The existing serialized run transition remains authoritative. Presentation state will enable Sync now for an enabled saved profile in either idle or blocked phase, provided no run is active. Requesting it clears the prior terminal presentation for the attempt but keeps committed provider data and the committed checkpoint; a previously pending full reset remains pending.

Cleanup intent remains durable after synchronization has been disabled. Presentation state will expose pending cleanup and offer the existing disable/cleanup operation as an explicit retry without scheduling network work. Startup and permission recovery may invoke the same idempotent cleanup path. Success clears checkpoints and pending cleanup; failure leaves synchronization disabled with a stable actionable problem.

Cancel remains cooperative run cancellation and does not delete the mirror. Folding cleanup into Cancel was rejected because it would violate the accepted lifecycle contract and turn a reversible control into destructive behavior.

### 7. Cover the fixes at unit-test boundaries before implementation

Every behavioral repair will start with a failing local unit regression test. Tests will exercise the fixed-identity verification policy, Compact `InstanceId`, redirect invariants, provider delete URI and selection, exception mapping, lifecycle cleanup retry, blocked-state control availability, serialized state transitions, and diagnostic allowlist/privacy rules. Existing fake transports, fake stores, content-resolver seams, and ViewModel/state tests will be extended; no instrumentation, Robolectric, or end-to-end suite will be added.

## Risks / Trade-offs

- [A successful `OPTIONS` response does not independently prove which client certificate was transmitted] → Keep fixed identity as the only configured client key, fail all observable resolution/handshake/authentication errors, and phrase diagnostics as configuration plus available evidence rather than proof.
- [The initial page may genuinely contain no events, leaving the user's empty-calendar symptom server-dependent] → Preserve the standards-defined unfiltered request and add boundary summaries that identify the first zero-count stage during the next real-device run.
- [OEM Calendar Provider implementations may add further restrictions] → Use the documented collection-URI shape, retain exact ownership constraints, map all provider-originated runtime failures, and keep cleanup retryable.
- [Additional summaries may increase log volume] → Emit bounded aggregate records only at page/provider transitions, reuse correlation fields, and never log per-event content.
- [Repeated manual retry can repeatedly reach the same permanent server problem] → Keep runs serialized, disable the control while active, and return to blocked with the same actionable category when the problem persists.
- [Catching broad provider runtime failures can hide programmer defects] → Catch only at the Android provider adapter boundary, record the concrete sanitized exception chain, preserve cancellation, and keep unit assertions for known mappings.

## Migration Plan

1. Ship the parser, transport-policy, provider-cleanup, state/UI, and diagnostic changes together so new retry controls always reach compatible adapters.
2. No stored profile or synchronization-state schema migration is required. Existing enabled blocked states immediately gain manual retry; existing disabled cleanup-pending states use the repaired cleanup path on startup or user retry.
3. Validate on Android 16 with the supplied Exchange environment: connection verification, unfiltered full sync, recurring exceptions, restart, blocked manual retry, disable cleanup, and preservation of unrelated calendars.
4. Rollback requires no data conversion. A rolled-back build can still read the stored profile and checkpoint; a cleanup left pending remains disabled rather than risking unintended deletion.
