## 1. Baseline and Exchange 15.2 compatibility

- [ ] 1.1 Run the existing connection, ActiveSync, calendar, and synchronization unit tests plus a debug assembly, and record any pre-existing failure before changing production sources.
- [ ] 1.2 Add local regression fixtures proving an Exchange product-build-family 15.2 endpoint succeeds when it advertises ActiveSync 16.1 or 14.1, remains compatible without product-version metadata, and rejects a header offering only the non-protocol value 15.2.
- [ ] 1.3 Confirm the existing highest-mutual-version negotiation passes those fixtures without adding `ActiveSyncVersion.V15_2`; change production negotiation only if the regression fixtures expose a real compatibility gap.

## 2. Profile-scoped in-memory HTTP sessions

- [ ] 2.1 Add failing infrastructure unit tests for cookie acceptance, same-name/domain/path replacement, server deletion, expiry pruning, Secure/host/domain/path matching, redirect-host isolation, and concurrent access without logging or persisting cookie data.
- [ ] 2.2 Implement the thread-safe in-memory cookie jar with OkHttp cookie matching until the cookie-policy tests pass, without adding a new cookie dependency.
- [ ] 2.3 Add failing unit tests for exact-profile session isolation, bounded eviction, reuse across newly constructed transports, and a different email/account/host/certificate profile receiving no prior cookies.
- [ ] 2.4 Implement the bounded profile-session registry containing cookie state and live process capability state, then keep all profile identity and cookie values out of diagnostic output.

## 3. Shared ActiveSync runtime and cold-session recovery

- [ ] 3.1 Add failing connection/capability/command client tests proving cookies from `OPTIONS` and permitted redirects reach later eligible requests, command responses update subsequent `FolderSync`/`Sync` pages, and ineligible redirect destinations receive none.
- [ ] 3.2 Refactor the secure transport factory to bind a profile session while preserving the selected mTLS key manager, combined server trust, manual HTTPS redirect policy, response bounds, and current timeout behavior.
- [ ] 3.3 Add failing remote-calendar tests proving a live capability session is reused, a cold process performs `OPTIONS` before a command despite persisted checkpoints, the persisted protocol version is retained when still offered, and a protocol-version change enters full-reset recovery before old keys are reused.
- [ ] 3.4 Implement capability-session recording and cold-session bootstrap across connection verification, capability discovery, retries, and continuation slices until the new tests pass.
- [ ] 3.5 Introduce one Android ActiveSync process runtime/factory in `:infrastructure`, wire one instance through `AppContainer` to both connection verification and calendar synchronization, and update composition regression tests without adding a DI framework or changing module direction.

## 4. Connection, network, TLS, and mTLS diagnostics

- [ ] 4.1 Add typed allow-listed diagnostic events, the non-fatal Android Logcat sink using stable tag `ExchangeSync`, process-local operation correlation, and bounded cycle-safe throwable formatting with centralized redaction; do not add unit tests for log emission or formatting.
- [ ] 4.2 Instrument local CA enumeration/parsing, trust-manager construction, KeyChain resolution, client key/chain availability, and selected-client-certificate handshake verification before existing failures are collapsed, excluding aliases, key material, certificate encodings, and raw exception output.
- [ ] 4.3 Instrument DNS/connect/secure-connect/request/response/failure stages and bounded response reading with sanitized host/path, method, command, status, timeout, exception/cause classes, and operation correlation, never logging URL queries, headers, cookies, or bodies.
- [ ] 4.4 Instrument connection Save/recheck, redirects, HTTP classification, capability-header validation, version selection, and ActiveSync command classification so every failure records its original cause and final stable category before returning the unchanged user-facing result.

## 5. Event-validation and synchronization diagnostics

- [ ] 5.1 Preserve stable validation reason codes plus command kind and available opaque `ServerId` at WBXML, FolderSync, Sync, and calendar application-data parsing boundaries, then emit sanitized invalid-event diagnostics before mapping to `PROTOCOL_DATA`; add no logging-specific unit tests.
- [ ] 5.2 Instrument event mapping/planning and Calendar Provider query, batch, checkpoint, cleanup, and ownership failures with the available event sync ID and synchronization correlation, excluding event content and raw provider values.
- [ ] 5.3 Add an Android-free typed `SyncDiagnosticsPort` to `:core`, inject its Logcat-backed implementation in production composition, and emit phase, retry, reset, window reduction, block, obsolete, cancellation, completion, and unexpected-exception events from synchronization execution without changing recovery policy.
- [ ] 5.4 Instrument periodic and execution worker input/result boundaries so invalid input, retries, blocks, cancellation, success, and unexpected exceptions remain traceable by generation and run token.

## 6. Verification, device diagnostics, and documentation

- [ ] 6.1 Run focused module unit tests after each behavior group, then run the full local unit-test suite and fix every regression without adding `src/androidTest`, instrumentation, Robolectric, or logging-specific tests.
- [ ] 6.2 Build and install the debug APK on an Android 16 device, collect `adb logcat` using the `ExchangeSync` tag for representative network and TLS/mTLS connection failures plus available calendar/synchronization failures, and inspect the records for actionable correlation and absence of cookies, account/email values, aliases, query strings, payloads, event content, and key material.
- [ ] 6.3 Run `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`, then run `$openspec-verify-change`, inspect the tracked diff for unrelated/generated/secret files, and run Codex `/review`, resolving every valid finding.
- [ ] 6.4 Immediately before archive, update all affected files under `docs/` with Exchange 15.2 versus ActiveSync version semantics, process-local cookie lifetime and cold bootstrap, Logcat field/redaction rules, and exact ADB collection commands; remove any stale implementation claims.
- [ ] 6.5 Re-run relevant tests, Lint, type checks, assembly, strict OpenSpec validation, documentation audit, and diff/secret inspection after review and documentation changes so the change is ready for specification sync and archive.
