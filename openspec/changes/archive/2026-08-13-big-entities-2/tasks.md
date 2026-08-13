## 1. Reproduce the high-element single-item failure

- [x] 1.1 Add a compact generated Calendar `Sync` fixture containing one valid recurring item, more than 200 attendees, and enough changed and deleted exceptions with attendee data to exceed 20,000 elements while remaining below 256,000 elements and every existing byte, depth, and inline-string limit.
- [x] 1.2 Add a RED `ActiveSyncRemoteCalendarTest` or `ActiveSyncCapacityRecoveryTest` regression at `WindowSize=1` that requires the generated item to decode, preserve its attendee and exception inputs, reach local page application, and make its returned collection key eligible for commit instead of returning `WINDOW_TOO_LARGE`.
- [x] 1.3 Add or extend focused Calendar Provider planning/adapter unit coverage proving the resulting oversized series and non-deleted exceptions retain their organizer and recurrence rows, suppress all non-organizer attendee rows according to the existing 100-entry policy, and preserve deleted exception identities without introducing duplicates.

## 2. Enlarge the bounded full-tree decoder capacity

- [x] 2.1 Change the default `WbxmlLimits.maxElements` from 20,000 to 256,000 without changing the 2 MiB document, depth, inline-string, HTTP response, or Calendar Provider sub-batch limits, then make the high-element regression from section 1 pass without adding a streaming or selective parser.
- [x] 2.2 Move every default-capacity fixture that currently depends on 20,001 elements above the new 256,000-element boundary and retain assertions for window halving, unchanged checkpoint replay, `wbxml_element_count` diagnostics, and terminal `PROTOCOL_DATA` without item skip at window one.
- [x] 2.3 Keep small custom-limit codec tests for exact accept/reject off-by-one behavior, malformed WBXML, excessive depth, oversized inline strings, and the unchanged document-size boundary; run the focused WBXML, remote-calendar, capacity-recovery, parser, mapper, and provider tests GREEN.

## 3. Preserve explicit exception clears in required provider fields

- [x] 3.1 Add a RED local JVM regression at the Calendar Provider planning/request seam for a recurrence exception whose supported empty property cancels series inheritance; require the generated request to contain the field-specific non-null Android default and no `NULL` for any required provider column.
- [x] 3.2 Replace blanket empty-to-null serialization with explicit provider-column policies: retain `NULL` clears for nullable columns, write documented default/none values for faithfully representable required columns, and fail planning as `PROTOCOL_DATA` before any provider call when a required value has no faithful representation.

## 4. Documentation and verification

- [x] 4.1 Update `docs/calendar-sync.md` and any directly affected current-state text to describe the 256,000-element bounded full-tree capacity, field-specific required-column clear encoding, successful handling of a high-fanout single item, and unchanged attendee suppression and terminal over-capacity behavior.
- [x] 4.2 Run `./gradlew :core:test :infrastructure:testDebugUnitTest`, then run `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`; resolve every regression, compiler warning, lint error, or build failure.
- [x] 4.3 Install the verified debug APK on the Android 16 device and manually retry the retained blocked checkpoint; confirm diagnostics progress from the 954,252-byte response through decoded calendar, attendee suppression, all provider sub-batches, checkpoint commit, and continued pagination without recording private event payloads.
- [x] 4.4 Run `$openspec-verify-change`, inspect the complete tracked diff for unrelated changes, generated files, the untracked `logs` input, local trust anchors, credentials, private endpoints, payload samples, and secrets, then run Codex `/review` and resolve all blocking findings before specification sync and archival.
