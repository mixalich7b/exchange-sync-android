## 1. WBXML capacity classification and adaptive recovery

- [ ] 1.1 Add failing WBXML reader regressions that distinguish document-byte and element-count capacity from malformed syntax, unsupported encoding/tokens, excessive depth, and oversized inline strings without relying on exception message matching.
- [ ] 1.2 Introduce typed, allow-listed WBXML read-limit outcomes and preserve them through the ActiveSync codec boundary while retaining the existing numeric safety limits and strict malformed-data behavior.
- [ ] 1.3 Add failing Calendar remote-page regressions proving document/element capacity maps to `WINDOW_TOO_LARGE`, while the same condition in `FolderSync` and non-page-scaled Calendar failures remain critical protocol outcomes.
- [ ] 1.4 Route page-scaled Calendar decoder capacity into the existing adaptive window result and make the focused codec and remote-calendar tests pass without changing response-body or provider-transaction recovery.
- [ ] 1.5 Add synchronization regressions using the real remote failure path to prove the collection checkpoint and committed calendar remain unchanged, window values halve through the supported sequence, a smaller page resumes pagination, and window-one remote capacity blocks without skipping data.

## 2. Run-scoped folder preparation

- [ ] 2.1 Add failing remote-calendar and profile-session regressions proving one successful `FolderSync` is reused across multiple pages, adaptive retries, and continuation slices with the same fence.
- [ ] 2.2 Add failing boundary regressions proving a new run token, cold session, protocol-version mismatch, profile change, invalid key, or full-reset outcome refreshes or invalidates prepared folder state as specified.
- [ ] 2.3 Add profile-bound, process-local prepared folder state to the existing bounded session registry and update remote folder preparation to reuse or clear it while leaving persisted checkpoint commit ordering unchanged.
- [ ] 2.4 Run the existing capability, redirect, cookie-continuity, folder-selection, invalid-key, and multi-page tests to verify session reuse does not cross profiles, lose cookies, conceal folder invalidation, or expose non-primary calendars.

## 3. Two-second request pacing

- [ ] 3.1 Add failing virtual-time unit tests for a shared profile-session pacer: first request immediate, next top-level synchronization exchange at least two seconds after prior completion, longer elapsed work/backoff adding no extra delay, and transport failure still establishing the next interval.
- [ ] 3.2 Add failing integration-level client tests proving capability and command requests share pacing, redirect hops remain inside one paced exchange, non-synchronization connection verification retains its existing behavior, and cancellation during a wait sends no pending request.
- [ ] 3.3 Implement the monotonic, coroutine-cancellable, serialized pacer in the process-local ActiveSync session and wrap complete top-level synchronization exchanges without using blocking sleep or persisting wall-clock timestamps.
- [ ] 3.4 Revalidate the synchronization fence immediately before dispatch after a pacing wait and make pagination, adaptive retry, continuation, cancellation, and redirect timing regressions pass.

## 4. Privacy-safe diagnostics

- [ ] 4.1 Add failing diagnostic-model and formatter regressions for WBXML document/element capacity, malformed/depth/inline distinctions, window recovery versus minimum-window block, and folder refresh/reuse/invalidation outcomes.
- [ ] 4.2 Emit correlated capacity and folder-preparation diagnostics using only bounded enums, window values, safe outcomes, generation, and run token; prove collection IDs, folder names, synchronization keys, profile identity, payloads, and exception message text cannot enter records.
- [ ] 4.3 Update remote and synchronization diagnostic regressions so an element-capacity page is no longer labeled `MALFORMED_WBXML`, while genuinely malformed data retains its critical protocol classification.

## 5. Verification and documentation

- [ ] 5.1 Run focused `:core` and `:infrastructure` unit tests after each RED-GREEN-REFACTOR group, then run `./gradlew test` and resolve every regression without adding instrumentation, Robolectric, integration, or end-to-end tests.
- [ ] 5.2 Update `docs/calendar-sync.md` and `docs/diagnostics.md` to match the implemented typed capacity recovery, run-scoped `FolderSync`, two-second top-level request pacing, minimum-window behavior, and privacy-safe fields; remove stale claims immediately before archival.
- [ ] 5.3 Run `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`, then validate implementation with `$openspec-verify-change` and resolve every valid mismatch.
- [ ] 5.4 Run Codex `/review` against the resulting diff, inspect tracked and untracked changes for unrelated/generated/secret material, and ensure local diagnostic files such as `logs` and `logs-after-reset` remain untracked before syncing specs and archiving.
