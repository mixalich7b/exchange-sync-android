## 1. Fixed client identity and redirect policy

- [x] 1.1 Add failing `:infrastructure` unit regressions proving a successful validated capability or command response is accepted when `Handshake.localCertificates` is empty, while missing KeyChain material, TLS failure, authentication rejection, and certificate mismatch when metadata is present retain their stable failures.
- [x] 1.2 Implement the fixed-identity acceptance policy at the shared transport boundary, remove mandatory `localCertificates` participation checks from capability and command paths, and emit configured-identity versus participation-metadata diagnostics until the new tests pass.
- [x] 1.3 Extend redirect regressions for OPTIONS and POST method/body preservation, HTTPS-only validation, five-hop limit, cycle rejection, and per-hop reporting; keep OkHttp automatic redirects disabled and refactor only after all transport tests are green.

## 2. ActiveSync recurrence compatibility

- [x] 2.1 Add failing decoder/value-parser regressions for protocol Compact DateTime `InstanceId`, recurring-event exception mapping, and malformed or extended non-protocol values that must reject the page without a checkpoint advance.
- [x] 2.2 Reuse the strict UTC Compact DateTime parser for `InstanceId`, preserve the existing stable exception identity mapping and protocol-data failure, and make the focused parser and decoder tests pass.

## 3. Privacy-safe synchronization progress

- [x] 3.1 Add failing diagnostic-model and formatter tests for sync mode, bounded response and command counts, empty response, `MoreAvailable`, key-advanced boolean, ownership action, provider-operation counts, cleanup trigger, and checkpoint outcome; assert that keys, account identities, row identifiers, timestamps, payload fields, and WBXML cannot be emitted.
- [x] 3.2 Extend the typed diagnostic events and centralized formatter allowlist in accordance with `docs/diagnostics.md` until the new allowlist, sanitization, exception-chain, and bounded-value tests pass.
- [x] 3.3 Add failing remote-page regressions that distinguish priming, unfiltered full, and incremental requests; valid empty responses; decoded Add, Change, and Delete counts; multiple pages; and key advancement without exposing either key value.
- [x] 3.4 Emit correlated request/response and decoder summaries from the existing unfiltered synchronization flow, retaining `SyncKey=0` priming and no `FilterType`, until the remote-page regressions pass.
- [x] 3.5 Add failing mapper, provider-planner, batch-application, and checkpoint regressions that identify the first zero-count or failed stage without calendar content, then emit the corresponding bounded correlated summaries until those tests pass.

## 4. Owned-calendar cleanup

- [x] 4.1 Add failing Calendar Provider store regressions proving deletion uses the collection URI plus sync-adapter parameters and a selection containing provider `_id`, stable account name, account type, and internal name; assert that `account_name_local` and every other unrelated calendar are never selected or deleted.
- [x] 4.2 Replace item-URI deletion with the fully scoped collection-URI operation, verify affected-row outcomes, and make create, reuse, repair, duplicate-owned-row, missing-row, and delete tests pass.
- [x] 4.3 Add failing adapter and lifecycle regressions for provider `IllegalArgumentException`, other provider runtime failures, access/security failures, and cancellation; require an actionable durable cleanup problem for provider failures and unchanged cancellation semantics.
- [x] 4.4 Map provider-originated runtime failures at the Android adapter boundary, emit sanitized ownership/cleanup diagnostics, and retain disabled cleanup-pending state across restart until retry succeeds.

## 5. Manual recovery controls

- [x] 5.1 Add failing `:core` and `:feature:settings` unit regressions for manual retry from enabled blocked state, serialization while active, preservation of committed checkpoints and pending full-reset intent, repeated blocking, and the absence of controls without a saved profile.
- [x] 5.2 Enable Sync now for idle or blocked non-active state through the existing run transition, clear only the prior attempt presentation, and make the state, ViewModel, and Compose presentation tests pass.
- [x] 5.3 Add failing lifecycle and presentation regressions for disabled cleanup-pending state, explicit cleanup retry without network scheduling, success after startup or permission recovery, and cooperative Cancel leaving the mirror intact.
- [x] 5.4 Expose cleanup-pending state and wire an idempotent retry action to the existing disable/cleanup path until the lifecycle and presentation tests pass.

## 6. Focused regression verification

- [x] 6.1 Run `./gradlew :infrastructure:testDebugUnitTest` and resolve every regression without weakening the protocol, ownership, TLS, or diagnostic assertions.
- [x] 6.2 Run the relevant `:core` and `:feature:settings` local unit-test tasks and confirm blocked retry, cleanup lifecycle, and UI-state behavior remain serialized and restart-safe.
- [x] 6.3 Inspect the implementation diff for unrelated changes, generated files, local CA material, credentials, endpoints, payload samples, and diagnostic privacy violations before broader verification.

## 7. Developer documentation

- [x] 7.1 Update `docs/connection.md` and any affected architecture text to describe fixed-identity TLS evidence accurately and explain why `RedirectTracker` remains authoritative with OkHttp automatic redirects disabled.
- [x] 7.2 Update `docs/calendar-sync.md` and scheduling documentation to describe Compact `InstanceId`, unfiltered full-sync semantics, one owned calendar versus unrelated OEM calendars, blocked manual retry, and durable cleanup retry without presenting unverified behavior as implemented.
- [x] 7.3 Update `docs/diagnostics.md` with every new event, field, severity, bound, and prohibited-value rule, remove stale statements, and verify all affected documentation agrees with the accepted specs and final code.

## 8. Full verification and real-device confirmation

- [x] 8.1 Run `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`, addressing all compiler, lint, and test failures before proceeding.
- [x] 8.2 On an Android 16 device, verify profile connection, an all-history full sync, recurring exceptions, restart and background runs, manual retry after a forced blocked outcome, disable cleanup, and preservation of the unrelated `account_name_local` calendar; inspect only privacy-safe diagnostics and do not commit device logs.
- [x] 8.3 Run `$openspec-verify-change` against the final implementation and reconcile any mismatch in code, delta specs, design, tasks, or developer documentation.
- [x] 8.4 Run Codex `/review` on the final diff, address validated findings with regression coverage, repeat relevant verification, and leave the change ready for spec sync and archive.
