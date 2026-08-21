## Why

Android Calendar Provider can rewrite a recurring event's normally absent `DTEND` to epoch zero while retaining its valid `DTSTART`, `DURATION`, and recurrence rule during timezone-database maintenance. A later partial ActiveSync `Change` that omits unchanged time fields then inherits the false epoch end from the clean local snapshot, blocks the response page as an invalid time range, and cannot progress until the owned calendar is reset.

## What Changes

- Interpret a clean recurring Calendar Provider snapshot according to Android's recurring-event representation: when `DTSTART`, a recurrence rule, and a valid positive `DURATION` are present, reconstruct the event end from `DTSTART + DURATION` instead of treating a conflicting epoch-zero `DTEND` as authoritative.
- Keep partial ActiveSync `Change` merging able to preserve unchanged recurring-event times after Calendar Provider timezone-database maintenance.
- Preserve the existing strict rejection of malformed or reversed time ranges supplied by Exchange, non-recurring event validation, dirty-row reset policy, page atomicity, and checkpoint ordering.
- Add a unit regression reproducing a recurring provider row with `DTEND=0`, valid duration, and a subsequent partial `Change` with omitted time fields.
- Update calendar synchronization documentation to describe the provider-snapshot normalization and its narrow trust boundary.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `calendar-sync`: make incremental merging resilient to a Calendar Provider tzdb-maintenance artifact on otherwise valid clean recurring rows without weakening server-data validation.

## Impact

- Affected code: Calendar Provider snapshot reconstruction in `:infrastructure`, plus focused local JVM tests for provider snapshot reading and partial page application.
- Affected specifications and documentation: `calendar-sync` and `docs/calendar-sync.md`.
- No new dependency, persistence format, network protocol, Android permission, user-interface, scheduling, or notification changes.

## Non-goals

- Modifying or repairing Android Calendar Provider itself, rewriting unrelated provider rows, or depending on OEM-private APIs.
- Treating every zero or invalid timestamp as absent, accepting malformed Exchange `StartTime` or `EndTime`, or suppressing ordinary event-validation failures.
- Adding automatic full reset, item skipping, per-item checkpoint advancement, retry-policy changes, or a new user-visible recovery flow.
