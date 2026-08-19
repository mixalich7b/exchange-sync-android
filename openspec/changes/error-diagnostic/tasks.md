## 1. Typed validation and diagnostic contracts

- [x] 1.1 Add RED `:core` tests proving every existing calendar mapping rejection exposes a stable typed rule and a nested exception failure exposes its exception index/path without changing the thrown failure type.
- [x] 1.2 Replace message-only core mapping failures with the minimal typed rule/path contract, retain readable messages, and make the focused `:core` tests pass under explicit API mode.
- [x] 1.3 Add RED infrastructure diagnostic-model and formatter tests for field-state/value rendering, deterministic ordering and chunk ordinals, the per-value and per-record bounds, and non-fatal projection/formatting failure.
- [x] 1.4 Implement typed calendar/provider failure snapshots, exhaustive field classifications, deterministic chunking, and dedicated formatter paths until the focused diagnostic tests pass.
- [x] 1.5 Add privacy regression cases containing unique markers in title, body, attendee, organizer, email/account, header, URL, and allowed location/time/recurrence fields; prove excluded and secret markers never format while allowed sanitized values remain.

## 2. ActiveSync event-parse failure detail

- [x] 2.1 Add RED application-parser and remote-calendar tests for malformed Add and Change values that require command kind, `ServerId`, stable rule, failing allowed scalar value, field presence, nested exception context, and synchronization correlation in emitted diagnostics.
- [x] 2.2 Implement the explicit raw-command safe projector at the application-data boundary, skip forbidden subtrees, attach only the projected snapshot to protocol-data failures, and make the parser/remote tests pass without retaining raw WBXML.
- [x] 2.3 Add regressions for attendee-, organizer-, title-, and body-related parse failures proving only structural presence/counts survive and no corresponding value enters the diagnostic event or throwable graph.

## 3. Mapping and provider-planning failure detail

- [x] 3.1 Add RED mapper/page-planner/adapter tests reproducing a partial Change whose response and prior snapshot form an equal or reversed range, asserting separate response, prior, and effective timestamps plus the typed relationship and validation rule.
- [x] 3.2 Implement mapping/planning safe-context propagation from the incoming mutation and existing provider snapshot, including failed exception path and bounded collection counts, and make the focused tests pass.
- [x] 3.3 Add RED representation-failure cases for recurrence, all-day alignment, timezone resolution, required provider values, meeting response, reminders, location, identifiers, and exception metadata; assert all permitted inputs and derived values are present and excluded values are absent.
- [x] 3.4 Complete the exhaustive event/exception projector and stable representation-rule mapping until every focused representation case passes without changing failure classification or provider planning behavior.

## 4. Failed Calendar Provider sub-batch detail

- [x] 4.1 Add RED projector tests covering every `CalendarProviderBatchOperation` variant, global and local indexes, target entity, existing/back-reference identities, all current provider columns, unknown value types, and structural-only attendee/organizer operations.
- [x] 4.2 Implement exhaustive provider-operation projection and filtered value rendering, including deterministic chunks that preserve every permitted value without arbitrary object `toString()` output.
- [x] 4.3 Add RED adapter tests proving a failed first or later sub-batch emits the existing aggregate outcome followed by detail for every attempted operation, preserves prior confirmed counts, marks the provider call outcome unknown, and never selects an unsupported causal operation index.
- [x] 4.4 Emit failed-sub-batch detail from the retained active plan for every permanent, security, runtime, remote, operation-application, and transaction-too-large provider failure while keeping successful sub-batches aggregate-only and making the adapter tests pass.
- [x] 4.5 Add failure-isolation regressions proving diagnostic projection, chunking, formatting, or sink failure cannot replace the original local-page outcome, mutate the calendar again, or advance a checkpoint.

## 5. Documentation and verification

- [x] 5.1 Run `./gradlew :core:test :infrastructure:testDebugUnitTest` and resolve every focused or regression failure without weakening the privacy assertions.
- [x] 5.2 Update `docs/diagnostics.md` and any affected `docs/calendar-sync.md` statements with the failure-only field policy, chunk correlation, attempted-operation semantics, collection commands, and explicit exclusions.
- [x] 5.3 Run `./gradlew test lintDebug :app:assembleDebug verifyBootstrap` and confirm compilation warnings, local tests, Android Lint, and the debug build all pass.
- [x] 5.4 Inspect the tracked diff and repository status for unrelated edits, generated files, raw payloads, event content, credentials, private endpoints, or local trust anchors; leave the untracked `logs` evidence outside the change.
- [x] 5.5 Validate the completed implementation with `$openspec-verify-change`, run Codex `/review`, and resolve all findings before specification sync and archival.
