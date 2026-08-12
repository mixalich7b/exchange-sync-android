## Why

A real unfiltered calendar synchronization is blocked at window one by a recurring meeting whose exception identifies the current user as an attendee but omits both an exception-level `ResponseType` and that attendee's optional `AttendeeStatus`. The parser currently treats the missing optional status as malformed protocol data instead of preserving the absent exception override so the occurrence can inherit the series response.

## What Changes

- Treat a recurrence exception's response override as absent when the exception supplies neither an authoritative `ResponseType` nor an unambiguous current-user attendee status.
- Continue deriving an exception override when exactly one current-user attendee supplies a supported status, while keeping an explicit exception `ResponseType` authoritative.
- Preserve strict rejection when the received meeting series itself cannot be classified, so the fix cannot silently manufacture an accepted response.
- Add unit regressions for the captured missing-status shape, inherited series presentation, explicit exception overrides, and checkpoint-safe page replay after protocol parsing succeeds.
- Do not change synchronization scope, ActiveSync commands, pagination/window recovery, Calendar Provider ownership, or persisted checkpoint semantics.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `calendar-sync`: Clarify that an exception without a usable response override inherits the recurring series response even when its attendee list contains the current user with an omitted status.

## Impact

- `:infrastructure` ActiveSync calendar application-data parsing and its local JVM tests.
- `:core` recurrence response mapping tests only if needed to prove the inherited presentation through the existing mapper contract; no public model or port change is expected.
- `openspec/specs/calendar-sync/spec.md` and `docs/calendar-sync.md` when the change is implemented and archived.
- No new dependency, Android API surface, network request, storage migration, or credential-handling change.
