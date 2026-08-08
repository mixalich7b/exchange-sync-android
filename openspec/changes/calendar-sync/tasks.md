## 1. Baseline and Android integration surface

- [ ] 1.1 Run the existing unit, Lint, and debug-assembly baseline and record any pre-existing failure before changing production sources.
- [ ] 1.2 Add the pinned AndroidX WorkManager version/library aliases and module dependencies without changing the existing four-module dependency direction.
- [ ] 1.3 Add only the required calendar and notification manifest permissions plus notification-channel and synchronization UI resources, then confirm production and unit-test sources still compile.

## 2. Core synchronization domain and transitions

- [ ] 2.1 Add failing `:core` tests for synchronization state defaults, generation and run-token fencing, phases, checkpoints, trigger coalescing, safe persisted failure categories, and corrupt/missing state normalization.
- [ ] 2.2 Implement the public core synchronization models, remote/local/state/scheduler/permission/problem ports, outcomes, and state-transition policy until the new domain tests pass under explicit API mode.
- [ ] 2.3 Add failing core tests for profile activation, run-now, cancellation, disable, enable, one-time invalid-key reset, bounded continuation, successful completion, and obsolete-generation no-op behavior.
- [ ] 2.4 Implement the lifecycle and execution-slice use cases in `:core`, including permission preconditions, generation checks at every side-effect boundary, retry classification, five-attempt budget, and last-success/problem updates.

## 3. Durable synchronization metadata

- [ ] 3.1 Add failing infrastructure tests for namespaced synchronization-state encoding, transactional multi-field updates, stable device ID creation, disabled migration of pre-change saved profiles, and rejection/defaulting of malformed metadata.
- [ ] 3.2 Refactor composition to provide one shared Preferences DataStore instance and implement the synchronization-state repository without broadening the existing profile credential payload.
- [ ] 3.3 Add regression tests proving persistence contains no password, private key, certificate bytes, raw response/event payload, or exception text while retaining the non-secret generation and ActiveSync checkpoints.

## 4. WBXML and ActiveSync value codecs

- [ ] 4.1 Add failing fixture tests for WBXML headers, multi-byte integers, inline strings, empty/content elements, code-page switches, unknown well-formed element skipping, and malformed size/depth/token limits.
- [ ] 4.2 Implement the bounded WBXML reader/writer and token tables for `AirSync`, `FolderHierarchy`, `Calendar`, and `AirSyncBase` until canonical fixtures pass without relying only on encoder-decoder round trips.
- [ ] 4.3 Add failing tests for ActiveSync date-time values, all-day boundaries, reminder values, recurrence variants, recurrence end rules, exception inheritance/removal, attendees, `MeetingStatus`, `ResponseType`, `ResponseRequested`, current-user attendee response, availability, sensitivity, body presence, and Windows time-zone blobs.
- [ ] 4.4 Implement pure ActiveSync calendar value parsers and immutable models that distinguish absent fields from explicitly empty fields, including series and exception meeting-response state.

## 5. ActiveSync primary-calendar client

- [ ] 5.1 Add failing tests for command URI percent encoding, `domain\login` user selection, stable device parameters, protocol headers, content type, highest-version choice from 14.0–16.1, rejection of a 12.1-only endpoint, and reuse of the existing HTTPS/mTLS redirect and failure policy.
- [ ] 5.2 Extract the minimum shared secure endpoint/transport components from the current connection verifier and implement the WBXML command request adapter without changing Save or recheck behavior.
- [ ] 5.3 Add failing protocol fixtures for `FolderSync(0)`, incremental folder hierarchy changes, unambiguous primary Calendar selection, no-primary/multiple-primary failure, initial `SyncKey=0`, unfiltered `GetChanges`, window 100, and `MoreAvailable` pagination.
- [ ] 5.4 Implement full and incremental folder/calendar command sequencing, protocol status mapping, and durable endpoint/version/folder/collection results.
- [ ] 5.5 Add failing tests for ActiveSync 14.0/14.1 supported-property ghosting, 16.0/16.1 omitted-field merge behavior, primary-Calendar pending/tentative/accepted/organizer/declined meeting fixtures, Add/Change/Delete/SoftDelete parsing, unsupported provisioning, invalid-key recovery, repeated invalidation, malformed items, and adaptive smaller-window retry.
- [ ] 5.6 Implement version-aware calendar and meeting-response merging, Calendar-only pending-invitation intake, one-way command enforcement, invalid-key reset outcomes, provisioning incompatibility, and adaptive server window selection without discovering Inbox or adding outbound calendar commands.

## 6. Exchange-to-Android event mapping

- [ ] 6.1 Add failing pure mapping tests for ordinary timed and all-day events, UID/ServerId identity, title/body/location, organizer, attendees, every `ResponseType`, response fallback/failure, tentative/confirmed/cancelled status, self-attendee status, server/tentative availability, reminder removal, and opaque 45%-toward-white event-color derivation.
- [ ] 6.2 Implement the platform-neutral event-to-provider operation model and scalar/meeting-response mapping so pending and tentative meetings receive semantic tentative fields plus the pale color while accepted and organizer-owned meetings clear the override.
- [ ] 6.3 Add failing mapping tests for daily, weekly, monthly, nth-day, yearly, finite, and infinite recurrences plus changed/deleted exceptions, inherited/overridden exception responses, and pending-to-accepted-to-pending transitions without changing ServerId identity.
- [ ] 6.4 Implement recurrence rule and exception-row mapping, including response-state inheritance and deterministic failure for response, recurrence, or time-zone data that cannot be represented without shifting identity or time.

## 7. Isolated Calendar Provider adapter

- [ ] 7.1 Add failing adapter-planning tests for the constant application account identity, `ACCOUNT_TYPE_LOCAL`, internal name, read-only calendar flags, complete ownership selection, same-display-name isolation, duplicate-owned-row repair, and missing-calendar recreation.
- [ ] 7.2 Implement owned-calendar resolution, creation, verification, and deletion through sync-adapter-qualified Calendar Provider URIs, keeping display/owner email out of ownership predicates.
- [ ] 7.3 Add failing tests for idempotent ServerId upsert, atomic `STATUS`/`AVAILABILITY`/`SELF_ATTENDEE_STATUS`/`EVENT_COLOR` transition and color clearing, child attendee/reminder replacement, recurrence exception linkage, sync-adapter deletion, all-or-nothing page plans, and a guard that rejects every operation targeting a non-owned calendar ID.
- [ ] 7.4 Implement ContentResolver/ContentProviderOperation batching for additions, partial meeting-response changes, event-color updates, deletions, reminders, attendees, and exceptions without changing event identity or ever scanning or mutating unrelated calendars.
- [ ] 7.5 Add failing crash-boundary tests proving calendar-first/key-second commit ordering, replay after a crash between those commits, no key advance after provider failure, and no duplicate rows after replay.
- [ ] 7.6 Connect the idempotent page adapter to synchronization checkpoints and implement transaction-size failure feedback so the remote client retries the unchanged key with a smaller window.

## 8. Profile activation and synchronization lifecycle

- [ ] 8.1 Extend `SaveConnection` tests first for successful first/changed profile activation after persistence, failed-check no-op behavior, post-persistence lifecycle failure without profile rollback, and unchanged manual recheck isolation.
- [ ] 8.2 Integrate the post-persistence activation port into Save while preserving validate-probe-commit ordering, connection diagnostics, and all existing connection failure behavior.
- [ ] 8.3 Add failing lifecycle tests for the exact disable order, profile retention, pending cleanup when permission is revoked, re-enable full reset, restart while disabled, old-generation late completion, and cancellation preserving the last committed page.
- [ ] 8.4 Implement cleanup/scheduling orchestration and generation/run-token invalidation until disable, re-enable, profile replacement, and cancellation tests pass.

## 9. WorkManager scheduling and bounded execution

- [ ] 9.1 Add failing pure scheduler-policy tests for one network-constrained 15-minute periodic trigger, unique immediate work, duplicate reconciliation, generation input, exponential 30-second backoff, five-attempt exhaustion, and periodic eligibility after persistent failure.
- [ ] 9.2 Implement the WorkManager scheduler, lightweight periodic trigger worker, unique bounded execution worker, and continuation scheduling while keeping all business decisions in tested core policy.
- [ ] 9.3 Add failing execution tests for soft time/page slice limits, `MoreAvailable` continuation, periodic/manual/profile trigger coalescing, process-recreated state, worker stop/cancel propagation, retry-result mapping, and critical-result persistence.
- [ ] 9.4 Implement the worker adapters and manual WorkerFactory so UI minimization and process recreation do not depend on an activity or ViewModel instance.

## 10. Permission and problem-notification flow

- [ ] 10.1 Add failing core tests for calendar permission gating before network access, grant-triggered continuation, denied/revoked blocked state, and notification-permission denial that does not block calendar synchronization.
- [ ] 10.2 Implement permission-status ports and lifecycle effects, with Activity-result requests owned by `:app` and no infrastructure dependency from `:feature:settings`.
- [ ] 10.3 Add failing tests for immediate critical categories, transient exhaustion, recovered invalid-key suppression, one notification per generation, safe localized content, settings deep link, and clear-on-success/disable/profile-change behavior.
- [ ] 10.4 Implement the notification channel and adapter with one ongoing notification, immutable safe pending intent, permission checks, deduplication, and no endpoint/login/response/event/certificate data.

## 11. Settings presentation and composition

- [ ] 11.1 Add failing ViewModel tests for recovered disabled/idle/queued/running/cancelling/blocked states, current phase and last success, Sync Now eligibility, Cancel visibility, Disable/Enable behavior, permission actions, persistent problems, and mutual exclusion with Save/recheck.
- [ ] 11.2 Extend settings state, ViewModel, Compose UI, and localized resources with synchronization status and controls while preserving current profile editing and ephemeral TLS diagnostics.
- [ ] 11.3 Add resource and composition regression tests, then wire the shared DataStore, synchronization use cases, protocol/provider/scheduler/notification adapters, permission launchers, WorkerFactory, and startup notification channel in `:app`.
- [ ] 11.4 Re-run all existing connection tests and add static dependency/manifest guards proving there is no built-in Exchange account, SyncAdapter, foreground service, exact-alarm path, new speculative module, or feature-to-infrastructure dependency.

## 12. Verification, manual validation, and documentation

- [ ] 12.1 Run the focused module test tasks after each group, then run `./gradlew test` and fix every regression without adding instrumentation, Robolectric, or a lint baseline.
- [ ] 12.2 Run `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`; resolve every compiler, warning-as-error, Lint, and packaging failure.
- [ ] 12.3 On an Android 16 device, manually verify first-save permissions, full history and future events, recurrence/reminders, a pending primary-Calendar invitation rendered paler through `DISPLAY_COLOR`, its accepted transition to normal color without duplication, background continuation after minimizing, 15-minute scheduling visibility, manual run/cancel, retry/problem notification, profile replacement cleanup, disable/re-enable, and survival of unrelated calendars.
- [ ] 12.4 Run `$openspec-verify-change`, inspect the complete tracked diff for unrelated files, generated artifacts, local CA material, endpoints, credentials, and other secrets, then run Codex `/review` and resolve all valid findings.
- [ ] 12.5 Immediately before archive, update every affected file under `docs/` to the final implemented behavior, remove statements that calendar sync/workers/reminders/notifications are unimplemented, and verify documentation does not claim any planned or manually unverified behavior as complete.
- [ ] 12.6 Re-run the relevant tests, Lint, type checks, build, strict OpenSpec validation, documentation audit, and diff/secret inspection after review and documentation changes so the change is ready for spec sync and archive.
