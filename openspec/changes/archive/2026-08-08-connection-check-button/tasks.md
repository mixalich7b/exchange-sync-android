## 1. Shared core verification and diagnostic contract

- [x] 1.1 Write failing `:core` JVM tests for shared draft verification: all validation errors stop before the verifier, a typed verifier failure is preserved, and success returns the validated profile plus ordered TLS diagnostics.
- [x] 1.2 Add Android-free TLS diagnostic values, the server-certificate-diagnostics failure category, and the shared draft-verification action; change successful low-level verification to carry diagnostics until the tests from 1.1 pass.
- [x] 1.3 Write failing `SaveConnection` regression tests proving it delegates to the shared verification action, persists exactly once only after success, propagates diagnostics, and preserves the existing persistence-failure behavior.
- [x] 1.4 Refactor `SaveConnection` and its action/result contracts to satisfy the tests from 1.3 without changing the stored profile model or repository format.
- [x] 1.5 Run all `:core` JVM tests and its production/test compilation checks before changing adapters.

## 2. Terminal TLS peer-chain diagnostics

- [x] 2.1 Extend the infrastructure X.509 test stubs as needed, then write failing JVM tests for leaf-to-issuer order, RFC 2253 subject/issuer values, hexadecimal serial numbers, validity instants, SHA-256 fingerprints, and rejection of an empty or unusable peer chain.
- [x] 2.2 Implement the internal X.509-to-core diagnostic conversion and deterministic diagnostics-unavailable failure needed to satisfy the tests from 2.1, without exposing certificate objects or encodings outside infrastructure.
- [x] 2.3 Write failing verifier tests proving that successful direct and redirected probes return diagnostics from only the terminal host/handshake, keep peer and local client chains distinct, and cannot report success without usable peer X.509 data.
- [x] 2.4 Capture OkHttp handshake peer certificates in the probe response and attach converted diagnostics only after terminal mTLS-use and ActiveSync capability checks succeed, preserving all existing redirect, timeout, and failure behavior.
- [x] 2.5 Run all `:infrastructure` JVM tests plus its production/test compilation and lint checks.

## 3. Recheck presentation behavior

- [x] 3.1 Write failing settings ViewModel tests for recheck availability after saved-profile loading, its absence without a saved profile, disabling on any unsaved edit, re-enabling when saved values are restored, and no automatic probe during loading or editing.
- [x] 3.2 Add the saved-profile snapshot, explicit Save/Recheck operation state, injected shared verification action, and derived form/control availability needed to satisfy the tests from 3.1.
- [x] 3.3 Write failing ViewModel tests proving Save and recheck cannot overlap, all form/certificate/button events are ignored during either operation, successful Save updates the snapshot and diagnostics, successful recheck performs no repository write, and both paths map every typed failure consistently.
- [x] 3.4 Implement Save/Recheck transitions to satisfy the tests from 3.3, including clearing diagnostics when an edit starts, when a new attempt starts, and after validation or connection failure.
- [x] 3.5 Write failing presentation/resource tests for the recheck labels and dirty-state guidance, operation-specific progress, the new diagnostics-unavailable error, localized diagnostic labels/dates, ordered certificate rendering data, and the absence of raw PEM/DER or private-key presentation.
- [x] 3.6 Add the secondary recheck button and adapt the existing connection result area into a reusable feedback component that renders terminal host and certificate details on success or the current actionable error on failure.
- [x] 3.7 Run all `:feature:settings` JVM tests plus its production/test compilation and lint checks.

## 4. Composition and integration checks

- [x] 4.1 Update the manual `:app` composition root to provide the shared verification action to Save and the settings ViewModel without adding a DI framework or reversing module dependencies.
- [x] 4.2 Inspect production wiring and add or adjust local JVM regression coverage to prove recheck uses the loaded saved profile, never calls profile replacement, and still performs no calendar, worker, reminder, or notification action.
- [x] 4.3 Compile all production and unit-test sources and assemble the debug APK after the complete vertical slice is wired.

## 5. Final verification, review, and documentation

- [x] 5.1 Run `./gradlew test`, `./gradlew lintDebug`, and `./gradlew :app:assembleDebug`, confirming that all checks pass with no instrumentation, Robolectric, live-server, or new production dependency added.
- [x] 5.2 Run `$openspec-verify-change` against every `connection-settings` delta scenario and resolve any implementation/specification mismatch before review.
- [x] 5.3 Run Codex `/review` on the resulting diff, address every accepted finding test-first, and rerun all affected focused checks.
- [x] 5.4 Immediately after implementation and review have stabilized, update every affected developer document, especially `docs/connection.md` and `docs/architecture.md`, so it describes recheck and ephemeral TLS diagnostics as implemented without presenting later calendar-sync work as complete.
- [x] 5.5 Rerun `./gradlew verifyBootstrap`, strict OpenSpec validation, and a final scenario/diff audit; confirm no generated files, certificate contents, credentials, private endpoints, or unrelated modifications are present and the change is ready for specification sync and archive.
