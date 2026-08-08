## Context

See `proposal.md` for motivation and `specs/connection-settings/spec.md` for the observable contract.

The current core verifier returns a payload-free success or a typed failure. `SaveConnection` owns draft validation, calls the verifier, and replaces the single stored profile only after success. The infrastructure probe retains the local client certificates from the terminal OkHttp handshake so it can prove mTLS identity use, but it discards the peer server certificate chain. The settings ViewModel loads profile values into the form and exposes only loading/save progress, connected status, and an optional error; it does not retain a saved-profile snapshot or successful connection details.

The change crosses `:core`, `:infrastructure`, `:feature:settings`, and the manual `:app` composition root. Dependency direction must remain unchanged, TLS and KeyChain APIs must remain in infrastructure, and all automated coverage must remain JVM unit tests.

## Goals / Non-Goals

**Goals:**

- Keep Save and manual recheck on one validation and connection-verification path so their network behavior and failure taxonomy cannot drift.
- Carry only immutable, presentation-safe server certificate metadata across the infrastructure-to-core boundary.
- Make diagnostics correspond unambiguously to the displayed, successfully checked profile and terminal HTTPS host.
- Serialize Save and recheck operations and keep their ViewModel transitions unit-testable.

**Non-Goals:**

- Reconstructing or downloading a chain that is absent from the validated OkHttp handshake.
- Determining or displaying whether system trust or a bundled private CA accepted the chain.
- Persisting diagnostics, timestamps, certificate bytes, or verification history.
- Changing the TLS trust manager, redirect policy, endpoint policy, or calendar-sync boundary.

## Decisions

### 1. Make draft verification a shared core use case

Introduce one Android-free draft verification action that performs the existing deterministic validation, converts a valid draft to a profile, calls `ConnectionVerifier`, and returns one of: all validation errors, a typed connection failure, or the verified profile plus TLS diagnostics. `SaveConnection` will delegate to this action and perform `repository.replace` only for its verified result. The settings ViewModel will invoke the same action directly for a manual recheck and will not call the repository.

This preserves the current verify-before-persist ordering while making it impossible for Save and recheck to acquire separate validation or verifier rules. Calling `ConnectionVerifier` directly from the ViewModel was rejected because it would bypass draft validation and duplicate result mapping. Calling `SaveConnection` for a recheck was rejected because even an idempotent DataStore replacement would violate the no-write contract and would conflate verification with persistence.

The low-level successful verifier result will carry a `TlsConnectionDiagnostics` value instead of being a payload-free singleton. The successful Save result will propagate the same value so both UI entry points receive identical diagnostics. A dedicated failure category for an unavailable or unusable peer X.509 chain will give both paths a deterministic localized result instead of reporting a misleading trust failure or a generic exception.

### 2. Cross module boundaries with certificate metadata, not certificate objects

Define pure core values for the terminal host and an ordered non-empty list of certificate diagnostics. Each certificate value contains an RFC 2253 subject name, RFC 2253 issuer name, uppercase hexadecimal serial number, validity instants, and an uppercase colon-delimited SHA-256 fingerprint of the DER encoding. The core contract will not expose `X509Certificate`, OkHttp types, PEM/DER content, exception messages, or trust-manager details.

Keeping platform and provider objects in infrastructure preserves the permitted dependency direction and makes the public result deterministic. Passing `X509Certificate` through core was rejected because it would leak transport representation and defer security-sensitive formatting to presentation. Passing prelocalized display strings from infrastructure was rejected because locale-aware labels and date rendering belong to `:feature:settings`.

### 3. Derive diagnostics only from the terminal successful handshake

Extend the internal probe response with the peer certificates from the response handshake while retaining the existing local certificates used to prove the selected client identity. The redirect loop will ignore peer chains from redirect responses. Only after the terminal response passes mTLS-use and ActiveSync capability evaluation will infrastructure convert that response's peer certificates into core diagnostics using the terminal URL host.

The converter will retain X.509 order as supplied by the validated handshake, require at least one usable X.509 certificate, and fail the check with the dedicated diagnostics-unavailable category if that invariant is not met. It will not synthesize an omitted root, append local trust anchors, or label every listed certificate as server-supplied; OkHttp and the platform may expose a cleaned validation chain rather than the exact wire list.

Extracting chains at every redirect was rejected because the requested diagnostics describe the connection whose response proves ActiveSync compatibility. Reading accepted issuers from the trust manager was rejected because that list is a set of possible trust anchors, not the chain used for this connection.

### 4. Track the saved snapshot and one in-flight operation in presentation state

The ViewModel will retain the last successfully loaded or saved `ConnectionProfile` as its private saved snapshot. Recheck is available only when that snapshot exists and the current draft is value-equal to it. Editing any field or selecting a different certificate makes the form dirty, clears successful diagnostics, and disables recheck; restoring every saved value makes recheck available again. A successful Save replaces the snapshot with the returned profile. A successful recheck leaves the snapshot and repository untouched.

Represent the active operation explicitly as Save or Recheck rather than adding independent booleans. While either operation is active, the ViewModel ignores editing, certificate-selection, Save, and recheck events. This avoids overlapping network calls and prevents a late result from being displayed against a draft different from the one checked. Both operations clear prior diagnostics when they start; success installs the new diagnostics, while validation or connection failure leaves diagnostics empty and uses the existing error presentation path.

Allowing edits during the asynchronous operation and comparing drafts afterward was rejected because Save could still persist the captured older draft while the form showed newer values. Maintaining separate `isSaving` and `isChecking` flags was rejected because invalid flag combinations would permit accidental concurrency.

### 5. Adapt the existing result area into a reusable connection feedback component

Keep the full-width Save button and add a localized full-width secondary recheck button only after a saved profile has loaded. The recheck button is disabled for unsaved edits and while any operation is active; localized supporting text tells the user to save changed values first. The active button shows the existing connection-check progress treatment with operation-appropriate text.

Extract the current connected/error text region into a small connection feedback composable. It will render exactly one current failure or, after success, the connected state followed by the terminal host and a numbered leaf-to-issuer certificate list. Labels and dates come from Android string/date resources; subject, issuer, serial, and fingerprint values are displayed verbatim from the safe core metadata. The screen is already vertically scrollable, so no dialog or navigation destination is needed.

A separate diagnostics screen was rejected as unnecessary for the expected short chain. Persisting diagnostics to keep them visible after recreation was rejected because it would introduce stale security information and a storage migration solely for a troubleshooting aid.

### 6. Preserve unit-only verification and existing dependency choices

Core tests will prove shared validation, verifier delegation, diagnostic propagation, persistence only on Save success, and no persistence on manual recheck. Infrastructure tests will use stub X.509 certificates to prove terminal-host selection after redirects, chain ordering and metadata conversion, SHA-256 fingerprints, and safe failure for an empty or non-X.509 peer chain. Feature tests will cover dirty-state availability, one-operation serialization, blocked edits, success/failure transitions, diagnostic clearing, and localized resource presence.

No new production dependency is needed: Java security APIs provide X.509 metadata and SHA-256, and the existing Compose/material stack can render the result.

## Risks / Trade-offs

- **The validated peer chain may differ from the certificates sent byte-for-byte by the server.** → Label it as the chain available from the validated terminal handshake, preserve its order, and never claim that an omitted or cleaner-added trust anchor was transmitted by the server.
- **Subject and issuer distinguished names can be long or contain organization details.** → Keep the existing scrollable screen, use wrapping text, and never log or persist the values.
- **Blocking form edits during a check is stricter than the current Save UI.** → Use one explicit short-lived operation state and visible progress so the checked draft and displayed result remain consistent.
- **A provider anomaly could produce a successful HTTPS response without a usable peer X.509 list.** → Fail closed with the dedicated diagnostics-unavailable category instead of displaying an unverifiable or partial success.
- **Certificate validity is an instant but users read local dates.** → Retain instants in core and format them with the device locale/time zone only in presentation.

## Migration Plan

1. Add the core diagnostic values and shared draft-verification action, then adapt Save and its unit tests without changing persistence format.
2. Capture and convert the terminal peer chain in infrastructure, including redirect and failure regression tests.
3. Add saved-snapshot and serialized-operation presentation behavior, the recheck control, reusable result area, localized resources, and ViewModel/resource tests.
4. Update manual composition, developer documentation, and the main specification during the normal apply/verify/archive workflow.
5. Run the complete unit, lint, compile, debug assembly, strict OpenSpec, review, and scenario checks required by `AGENTS.md`.

Rollback removes the new action, diagnostic payload, and presentation controls. No stored-data migration or server-side rollback is required because the profile schema and remote state do not change.
