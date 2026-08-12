## 1. Reproduce response classification boundaries

- [ ] 1.1 Add a RED `ActiveSyncCalendarApplicationParserTest` regression for an ActiveSync 16.1 recurring `Add` with a classifiable series response and an exception whose current-user attendee omits both exception `ResponseType` and `AttendeeStatus`; require the parsed exception response to remain `Absent` and the mapped occurrence to inherit the series presentation.
- [ ] 1.2 Add focused response-resolver and parser guard tests proving an explicit exception `ResponseType` remains authoritative, exactly one supported current-user attendee status still produces an override, a missing or ambiguous optional status produces no override, and required received-series classification remains strict.
- [ ] 1.3 Add a RED remote-calendar regression using the same synthetic command shape to prove the page returns its next checkpoints instead of `PROTOCOL_DATA`, while an actually unclassifiable received series still returns a critical failure without a next checkpoint.

## 2. Implement optional exception response inference

- [ ] 2.1 Introduce distinct optional and strict current-user response-resolution paths that share supported status mapping but cannot silently weaken required meeting classification.
- [ ] 2.2 Change recurrence-exception parsing to use optional inference only when its own `ResponseType` is absent, preserving `Absent` when no unambiguous attendee status exists and leaving explicit response handling unchanged.
- [ ] 2.3 Run the focused parser, resolver, remote-calendar, and recurrence-mapper tests GREEN, then refactor duplicated matching or status-mapping logic without changing public core models or unrelated ActiveSync parsing.

## 3. Preserve merge and replay behavior

- [ ] 3.1 Add or extend local JVM mapping regressions proving an absent response on a new exception inherits the series response and an absent response on a partial change preserves a previously synchronized explicit exception override.
- [ ] 3.2 Run `./gradlew :core:test :infrastructure:testDebugUnitTest` and confirm the corrected page still follows provider-first, checkpoint-second application with no item skip, partial page commit, window-policy change, or raw payload diagnostics.

## 4. Documentation and verification

- [ ] 4.1 Update `docs/calendar-sync.md` and any directly affected current-state text to describe optional exception response inference, series inheritance, and strict series failure without presenting unimplemented behavior as complete before the code is ready.
- [ ] 4.2 Run `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`; resolve every regression, compiler warning, lint error, or build failure.
- [ ] 4.3 Install the verified debug APK on the Android 16 device and manually retry the retained blocked checkpoint; confirm the previously failing page applies and pagination continues without committing device logs or private event data.
- [ ] 4.4 Run `$openspec-verify-change`, inspect the complete tracked diff for unrelated changes, generated files, local trust anchors, credentials, endpoints, payload samples, and secrets, then run Codex `/review` and resolve all blocking findings before specification sync and archival.
