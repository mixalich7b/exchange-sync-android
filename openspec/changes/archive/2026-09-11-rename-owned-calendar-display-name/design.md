## Context

The owned calendar adapter already scopes provider operations by the stable
account name, account type, internal calendar name, and resolved provider ID.
Calendar creation writes a display name, while the current startup path only
reconciles synchronization scheduling. See the proposal and delta spec for the
user-visible behavior.

## Goals / Non-Goals

**Goals:**

- Create future owned calendars with display name `Exchange-sync`.
- Rename the existing owned calendar once on startup after an upgrade.
- Preserve the calendar row, events, reminders, and provider ID.
- Retry safely when provider access is unavailable.

**Non-Goals:**

- Changing the stable ownership tuple or package/application ID.
- Renaming unrelated calendars or calendars identified only by visible name.
- Adding a settings control or changing synchronization semantics.

## Decisions

- Keep the display name in `OwnedCalendarIdentity` so creation and migration
  share one source of truth.
- Add a provider update operation scoped by the complete ownership tuple and
  the resolved calendar ID. Updating in place preserves all child rows; delete
  and recreate was rejected because it would risk losing data and provider
  identity.
- Run a small infrastructure migration from `Application.onCreate` before
  scheduling reconciliation. Persist a DataStore completion marker only after
  the provider query/update succeeds; a missing owned calendar is also a
  successful no-op because normal synchronization will create it with the new
  name. A marker avoids repeated provider writes while retrying failures.
- Keep the migration outside `:core`: it is a startup/provider compatibility
  concern and does not affect synchronization ports or domain behavior.

## Risks / Trade-offs

- [Calendar Provider access is denied at startup] → Do not set the marker;
  retry on a later start and let the existing permission flow recover access.
- [Provider exposes duplicate owned rows] → Apply the update only to rows
  resolved through the existing complete ownership identity; do not broaden
  selection by display name.
- [A user manually renamed the owned calendar before migration] → The first
  upgrade migration still enforces the requested product name; later starts
  are skipped by the durable marker.

## Migration Plan

1. Ship the new display name, scoped update operation, and startup migration.
2. On upgrade startup, query owned rows and update them in place; write the
   completion marker only after success.
3. If the operation fails, leave the marker unset so the next application
   start retries it. Rollback is safe because the stable ownership tuple and
   calendar contents are unchanged.
