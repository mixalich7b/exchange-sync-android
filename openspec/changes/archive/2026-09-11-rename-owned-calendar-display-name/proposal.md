## Why

The Android Calendar Provider currently exposes the application-owned calendar
with an implementation-oriented package name instead of a user-facing product
name. Existing installations must retain their events while the calendar is
renamed to `Exchange-sync` after upgrading.

## What Changes

- Use `Exchange-sync` as the display name for newly created owned calendars.
- On the first launch after the update, find the existing calendar by its
  stable ownership identity and rename it without deleting or recreating its
  events.
- Make the migration safe to retry when Calendar Provider access fails and
  ensure unrelated calendars are never modified.
- Do not change the stable account or internal ownership identifiers.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `calendar-sync`: Change the user-visible name of the owned calendar and add
  an upgrade migration preserving its ownership and contents.

## Impact

The Calendar Provider adapter and its unit tests will gain a scoped update
operation. Application startup wiring will run the migration before normal
synchronization scheduling. No new dependency or public core API is needed.
