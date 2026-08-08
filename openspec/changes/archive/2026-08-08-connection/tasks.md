## 1. Build and local trust setup

- [x] 1.1 Pin compatible stable OkHttp, DataStore Preferences, coroutines, and lifecycle/ViewModel dependencies in the version catalog, add only the required module dependencies, and confirm the existing module dependency direction remains acyclic.
- [x] 1.2 Declare the application `INTERNET` permission and add the resources needed for localized connection-form labels, progress, status, validation, and failure messages without adding any server address or credential to tracked files.
- [x] 1.3 Add an explicit ignore rule for `infrastructure/src/main/assets/tls/`, move the five local root/issuing CA files from `tls_certs_tmp/` into that ignored asset location, and verify both the destination files and their certificate contents remain untracked.
- [x] 1.4 Document the optional local-CA asset setup, supported PEM/DER inputs, clean-checkout system-trust behavior, and connection-related development commands in `README.md` and `AGENTS.md`.
- [x] 1.5 Run the dependency/module compile checks and a debug assembly before introducing connection behavior.

## 2. Core connection policy

- [x] 2.1 Write failing JVM tests for empty and populated connection drafts/profiles and every email, `domain\login`, hostname, and certificate-alias validation scenario, then implement the Android-free models and validator that reports all invalid fields without normalizing prohibited connection syntax.
- [x] 2.2 Write failing JVM tests for the stable validation, certificate, network, TLS, HTTP, and ActiveSync failure taxonomy, then implement the core result and error types without raw exceptions, response bodies, or key material in their public presentation data.
- [x] 2.3 Define Android-free single-profile repository and connection-verifier ports, then write failing JVM tests for the save use case covering no probe on invalid input, no persistence on probe failure, exactly one atomic replacement after success, and preservation of a previously saved profile on every failure.
- [x] 2.4 Implement the save use case to satisfy the validate-probe-commit tests, including explicit successful connected state data and no calendar, worker, reminder, or notification side effects.
- [x] 2.5 Run all `core` JVM unit tests and its compile/lint checks before adding adapters.

## 3. Persistence and client-certificate adapters

- [x] 3.1 Write failing JVM tests for absent, complete, and malformed single-profile preference data plus atomic replacement semantics, then implement the Preferences DataStore profile codec and repository storing only email, `domain\login`, hostname, and KeyChain alias.
- [x] 3.2 Write failing JVM tests for fixed-alias key-manager selection and missing key/chain outcomes, then implement the pure key-manager policy used to expose only the chosen key and certificate chain to TLS.
- [x] 3.3 Implement the thin Android KeyChain resolver that obtains the private key and certificate chain only at probe time and maps missing, revoked, or inaccessible aliases to the typed re-selection failure without serializing or logging key material.
- [x] 3.4 Run all `infrastructure` persistence and client-credential JVM unit tests plus its compile/lint checks.

## 4. Server trust and ActiveSync probe

- [x] 4.1 Write failing JVM tests for an absent asset directory, valid PEM and DER X.509 inputs, multiple certificates, malformed inputs, and unsupported certificates, then implement dynamic loading from `assets/tls` without requiring that directory at build time.
- [x] 4.2 Write failing JVM tests for the composite trust manager accepting a chain trusted by either system or local trust, rejecting a chain rejected by both, and preserving missing/invalid-local-CA diagnostics, then implement system-first combined trust with no trust-all fallback.
- [x] 4.3 Write failing JVM tests for the exact initial HTTPS port-443 ActiveSync URL, relative and cross-host HTTPS redirect resolution, preservation of `OPTIONS`, the five-redirect limit, loop detection, HTTP-downgrade and malformed-`Location` rejection, whole-chain timeout policy, HTTP status classification, capability-header token parsing, supported-version intersection, and required `FolderSync` plus `Sync` commands, then implement the pure request, redirect, response, and failure policies.
- [x] 4.4 Implement the OkHttp verifier using the selected fixed-alias key manager, combined trust manager, and normal hostname verification at every destination; disable OkHttp's implicit redirect rewriting, explicitly repeat `OPTIONS` across at most five HTTPS redirects, require a terminal HTTP 200 capability response, and prove the selected leaf certificate appears in the terminal handshake before returning success.
- [x] 4.5 Add regression-focused JVM tests for exception-to-error mapping across DNS, connection, timeout, server-chain, hostname, local-CA, client-certificate/mTLS, unsafe/malformed/cyclic/excessive redirects, 401/403, 404/405, 5xx, and ActiveSync incompatibility categories without exposing raw exceptions to UI-facing results.
- [x] 4.6 Run all `infrastructure` JVM unit tests and its compile/lint checks before presentation wiring.

## 5. Settings presentation

- [x] 5.1 Write failing JVM tests for initial empty and saved-profile loading, field edits without network or persistence, certificate selection and cancellation, and retention of the attempted draft after failures, then implement the immutable settings state and ViewModel behavior.
- [x] 5.2 Write failing JVM tests for showing all field errors, mapping every typed connection failure to actionable presentation data, success status, visible progress, and suppression of simultaneous Save attempts, then implement those ViewModel transitions.
- [x] 5.3 Replace the static Compose screen with the four-value form, Android certificate-selection action, fixed HTTPS/443 context, Save/progress controls, field validation messages, actionable connection error, and connected status while keeping Android APIs outside `feature:settings`.
- [x] 5.4 Run all `feature:settings` JVM unit tests and its compile/lint checks.

## 6. Application composition

- [x] 6.1 Add manual application composition for the DataStore repository, KeyChain credential resolver, local/system trust providers, OkHttp verifier, save use case, and settings ViewModel without introducing a dependency-injection framework.
- [x] 6.2 Wire `MainActivity` to `KeyChain.choosePrivateKeyAlias` for the entered HTTPS host on port 443 with the current alias preselected; feed a returned alias into the ViewModel and leave the prior alias unchanged on cancellation.
- [x] 6.3 Verify through JVM presentation tests and production-code inspection that opening/editing the screen never probes, an unavailable alias stops before HTTP, failed probes never replace saved data, and no passwords, private keys, calendar operations, workers, reminders, or notifications were introduced.
- [x] 6.4 Run application/module JVM unit tests, compile checks, and lint after the complete vertical slice is wired.

## 7. Final verification

- [x] 7.1 Run the complete JVM unit-test suite and confirm no instrumentation, Robolectric, MockWebServer, or live-server tests were added.
- [x] 7.2 Run debug lint and assemble the installable debug APK with the local CA assets present.
- [x] 7.3 Verify the ignored CA files do not appear in Git, temporarily make the local asset directory unavailable, rerun the complete JVM unit tests, lint, and debug assembly, then restore the local files and confirm `tls_certs_tmp/` is no longer used.
- [x] 7.4 Compare the implementation against every `connection-settings` and modified `project-bootstrap` scenario, run strict OpenSpec validation, and confirm the final diff has no generated files, certificate contents, credentials, private endpoints, or unrelated modifications.

## 8. Review corrections

- [x] 8.1 Write failing JVM presentation tests with a suspended initial profile lookup proving loading progress and suppression of field edits, certificate changes, and Save, then implement an explicit initial-loading state whose successful, empty, and failed terminal results atomically unlock the form.
- [x] 8.2 Disable every Compose form control and the Activity certificate-selector action during initial loading, add the localized loading presentation, and run all `feature:settings` JVM unit tests plus compile/lint checks.
- [x] 8.3 Write failing JVM classifier tests proving that only a structured `PKIXReason.NO_TRUST_ANCHOR` can map absent or invalid local assets to their local-CA categories while expired and ambiguous server-certificate failures map to server trust, then implement the structured classification without exception-message matching and run all `infrastructure` JVM unit tests plus compile/lint checks.
- [x] 8.4 Rerun the complete JVM unit-test suite, debug lint, debug assembly, strict OpenSpec validation, and scenario/diff audit after both review corrections.

## 9. Second review corrections

- [x] 9.1 Write a failing JVM classifier test for an `SSLHandshakeException` wrapping a plain `CertificateException`, then classify the nested server-certificate failure before the generic handshake fallback and run the focused classifier tests.
- [x] 9.2 Write a failing JVM verifier test proving synchronous TLS transport construction uses an injected background dispatcher, then add the dispatcher boundary inside the whole-probe timeout and run the focused verifier tests.
- [x] 9.3 Run all `infrastructure` JVM unit tests plus its compile/lint checks after both corrections.
- [x] 9.4 Rerun the complete JVM unit-test suite, debug lint, debug assembly, strict OpenSpec validation, and scenario/diff audit, then repeat OpenSpec verification.
