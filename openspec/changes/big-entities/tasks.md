## 1. Bound attendee materialization

- [ ] 1.1 Add RED unit regressions for top-level events and recurrence exceptions at 100 and 101 effective non-organizer attendees, oversized meetings without an organizer, small-to-large and large-to-small updates, and response classification from the complete pre-suppression attendee list.
- [ ] 1.2 Implement the 100-attendee provider-planning policy so oversized lists still replace old non-organizer rows but emit only the supplied organizer representation, including inherited exception attendees; keep the protocol decoder and semantic mapper lossless.
- [ ] 1.3 Refactor the attendee-limit implementation around one named policy constant and verify the focused planner and mapping tests are GREEN without changing ordinary-event fidelity.

## 2. Plan dependency-safe provider sub-batches

- [ ] 2.1 Add RED pure unit tests for empty, 50-operation, 51-operation, and multi-batch plans; same-sub-batch back references; references to inserts from earlier sub-batches; and rejected forward, missing, or invalid insert results.
- [ ] 2.2 Introduce the structured sub-batch/result model and dependency-aware cursor that emits at most 50 consecutive operations, rewrites same-batch references to local indexes, and rewrites earlier-batch references to validated provider row identifiers.
- [ ] 2.3 Refactor batching around one named 50-operation policy constant and verify the cursor preserves canonical planner order for event, organizer, attendee, reminder, and exception operations.

## 3. Apply one Android provider sub-batch

- [ ] 3.1 Add RED Android-local gateway tests for local back-reference conversion, explicit existing-row references, returned event and exception insert identifiers, malformed or missing `ContentProviderResult` values, and typed `TransactionTooLargeException` propagation.
- [ ] 3.2 Change the Calendar Provider gateway contract to apply exactly one sub-batch and return structured validated insert results while retaining sync-adapter URIs and every owned-calendar predicate.
- [ ] 3.3 Run the focused gateway suite GREEN and refactor conversion/result parsing without exposing Android provider types outside `:infrastructure`.

## 4. Orchestrate the page and preserve idempotency

- [ ] 4.1 Add RED adapter tests proving one call for at most 50 operations, multiple calls capped at 50, no call for an empty plan, fence and cancellation checks before every call, immediate stop after failure or obsolescence, and page success only after the final sub-batch.
- [ ] 4.2 Implement the page-scoped sub-batch loop under the existing mutation lock, recording successful insert results between calls and returning only the cumulative confirmed operation count.
- [ ] 4.3 Add RED stateful replay regressions for interruption or ambiguous failure after a confirmed prefix, including partially replaced attendees, reminders, and recurrence exceptions; verify the unchanged checkpoint replay converges without duplicate parent or child rows.
- [ ] 4.4 Adjust query/planner replacement ordering only where the replay regressions require it, keep `_SYNC_ID` upsert and owned-calendar scoping intact, and run the focused adapter/replay suite GREEN.

## 5. Preserve checkpoint and capacity behavior

- [ ] 5.1 Add RED synchronization-use-case tests showing that a later bounded sub-batch capacity failure never stores the returned SyncKey, halves `WindowSize` above one, blocks with `CALENDAR_PROVIDER` at one, and never attempts a following sub-batch.
- [ ] 5.2 Update adapter outcome contracts and capacity handling as needed so only a fully applied page can advance its checkpoint, while partial confirmed prefixes remain replayable and failed-call effects remain explicitly unknown.
- [ ] 5.3 Run `./gradlew :core:test :infrastructure:testDebugUnitTest` GREEN and refactor shared outcomes without adding Android dependencies to `:core` or `:feature:settings`.

## 6. Add privacy-safe diagnostics

- [ ] 6.1 Add RED formatter and adapter diagnostic tests for oversized-attendee suppression, total and per-sub-batch counts, ordinal and cumulative confirmed progress, one-batch completion, and later ambiguous failure; assert that identities, content, provider row IDs, SyncKeys, and payloads are absent.
- [ ] 6.2 Implement allow-listed aggregate attendee-suppression and provider sub-batch diagnostic records, distinguishing the confirmed prefix from the unknown outcome of a failing Binder call.
- [ ] 6.3 Run the focused diagnostic tests GREEN and refactor duplicate count/summary construction while retaining existing correlation and redaction policies.

## 7. Documentation and release verification

- [ ] 7.1 Update `docs/calendar-sync.md`, `docs/diagnostics.md`, and any affected architecture text to document organizer-only representation above 100 attendees, non-atomic bounded provider calls, unchanged-checkpoint replay, and diagnostic fields without presenting unimplemented behavior as current before the code is complete.
- [ ] 7.2 Run `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`; resolve every regression, warning-as-error, lint error, or build failure.
- [ ] 7.3 Run `$openspec-verify-change`, inspect the complete tracked diff for unrelated changes, generated files, and secrets, then run Codex `/review` and resolve all blocking findings before specification sync and archival.
