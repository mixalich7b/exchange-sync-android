## Why

The application can currently verify and save an Exchange connection but cannot expose the server's primary calendar on the device or keep it current. This change delivers the first usable product behavior: a safe, one-way Exchange ActiveSync mirror with background execution, explicit user controls, and actionable failure reporting.

## What Changes

- Synchronize the configured mailbox's primary Calendar collection into one read-only, application-owned Android calendar, including all history and future items returned by the server, recurrence, exceptions, attendees, reminders, and pending or tentative meeting invitations with their response state.
- Represent meetings that have not been accepted with Android tentative/invited semantics and a deterministic paler per-event color, removing that color override when the meeting becomes accepted or organizer-owned.
- Require ActiveSync 14.0 or newer for calendar synchronization so the server can provide the meeting `ResponseType` needed for reliable response-state mapping.
- Add resumable full and incremental Exchange ActiveSync `FolderSync` and `Sync` processing over the existing HTTPS/mTLS transport, with durable synchronization keys and idempotent Calendar Provider writes.
- Reset the owned calendar and begin a new full synchronization after a new or changed profile is successfully saved.
- Continue synchronization independently of the settings UI, schedule best-effort periodic work at Android's 15-minute minimum interval, and apply retry with exponential backoff to transient failures.
- Add controls to start synchronization immediately, cancel an in-progress run, disable synchronization while retaining the profile and clearing the owned calendar, and re-enable it with a full synchronization.
- Report critical or persistently failing background synchronization through a deduplicated, user-actionable system notification and an equivalent persisted UI state.
- Prevent stale, cancelled, or previous-profile work from writing after a profile change or disable operation, and scope every Calendar Provider mutation to the application's own calendar.
- Preserve the current manual dependency composition and module dependency direction while adding the Android calendar, WorkManager, notification, and runtime-permission adapters required by the change.
- Non-goals: bidirectional synchronization, local event editing or upload, accepting, tentatively accepting, or declining invitations from the application, synchronizing meeting-request messages from Inbox, secondary/shared calendars, exact 15-minute execution, push synchronization, Android's built-in Exchange account integration, ActiveSync device-policy provisioning, and support below Android 16. Pending invitations are sourced only from meeting items returned by the primary Calendar collection.

## Capabilities

### New Capabilities

- `calendar-sync`: One-way ActiveSync discovery and synchronization of the primary Exchange calendar, including meeting response state and pending-invitation presentation, into an isolated read-only Android calendar.
- `sync-scheduling`: Durable immediate and periodic synchronization lifecycle, full reset, cancellation, enable/disable controls, retry, and progress state.
- `sync-problem-notifications`: Runtime permission handling and persistent user notification for background synchronization problems that require attention.

### Modified Capabilities

- `connection-settings`: A successful new or changed profile save now activates a calendar reset and full synchronization after profile persistence instead of ending without synchronization side effects.

## Impact

- `:core` gains platform-independent synchronization models, state transitions, use cases, failure taxonomy, and adapter contracts.
- `:infrastructure` gains ActiveSync WBXML/calendar clients, checkpoint persistence, Calendar Provider access, WorkManager adapters, and notification delivery.
- `:feature:settings` gains synchronization state and controls without depending on `:infrastructure`.
- `:app` gains manifest permissions, WorkManager/manual composition wiring, and notification-channel setup.
- The version catalog and Android modules gain pinned AndroidX WorkManager dependencies; no dependency-injection framework, AccountManager account, SyncAdapter, instrumentation suite, or additional speculative Gradle module is introduced.
- Persisted application state expands with non-secret synchronization enablement, generation, device identity, protocol, folder, collection, progress, and failure metadata; passwords, private keys, certificate encodings, server event payload archives, and secrets remain excluded.
