## Context

See `proposal.md` for motivation and the delta specs for normative behavior. The current application has one verified profile in Preferences DataStore, a reusable HTTPS/mTLS ActiveSync `OPTIONS` verifier, manual dependency composition in `:app`, settings presentation in `:feature:settings`, platform-independent connection policy in `:core`, and Android/HTTP adapters in `:infrastructure`. It has no Calendar Provider permissions, ActiveSync WBXML command codec, synchronization state, background work, or notifications.

The implementation must retain the existing dependency direction, Android 16-only boundary, unit-only automated test policy, system-plus-local CA trust, KeyChain private-key boundary, and direct-install distribution. Android periodic work is inexact and long-running work is quota-constrained, so a full mailbox history cannot be modeled as one activity coroutine or one unbounded worker invocation.

## Goals / Non-Goals

**Goals:**

- Make every synchronization slice restartable, idempotent, and fenced to the active profile generation.
- Keep protocol, mapping, retry, and state-transition policy testable on the JVM while leaving Android APIs behind narrow adapters.
- Reuse the existing connection transport, error categories, DataStore instance, module boundaries, and manual composition style.
- Make ownership predicates explicit enough that a bug cannot broaden a Calendar Provider mutation to unrelated calendars.
- Bound each worker invocation while allowing an arbitrarily large server-returned calendar to complete across continuations.
- Preserve and visibly distinguish pending and tentative meetings returned by the primary Calendar without expanding synchronization to Inbox.

**Non-Goals:**

- Add a new Gradle module, DI framework, database, AccountManager account, SyncAdapter, foreground service, exact alarm, or instrumentation test stack.
- Implement ActiveSync `Provision`, `Ping`, synchronization of Inbox meeting-request messages, outbound meeting responses, or calendar writes to the server.
- Cache complete Exchange payloads outside Calendar Provider or retain diagnostic response bodies.
- Guarantee that every third-party calendar UI renders every Exchange-specific property identically.

## Decisions

### 1. Keep the four-module architecture and add ports at the existing boundaries

`:core` will own immutable synchronization models, failure categories, state transitions, event representations, and ports for the remote calendar, checkpoint state, local calendar, scheduling, permission status, clock, and problem reporting. Use cases will cover profile activation, run-now, cancel, disable, enable, and execution of one resumable synchronization slice.

`:infrastructure` will implement ActiveSync/WBXML, DataStore state, Calendar Provider operations, WorkManager workers/scheduler, KeyChain-backed command transport, and Android notifications. `:feature:settings` will consume only core use cases and state flows. `:app` will configure one shared DataStore, the manual object graph, worker construction, manifest permissions, Activity-result permission launchers, and notification channels.

Alternative: split protocol, calendar, persistence, and workers into new modules. Rejected because the responsibilities are not yet reused independently and the current permitted dependency graph already isolates Android implementations from presentation. The change creates stable package-level boundaries first; a later OpenSpec change can extract a module if reuse or build isolation justifies it.

### 2. Use one durable generation-fenced state machine

Synchronization state will include:

- `enabled` and monotonically increasing `generation`;
- `fullSyncRequired` and whether one invalid-key recovery has already been used by the logical run;
- stable random ActiveSync `deviceId` and constant application `deviceType`;
- selected protocol version, resolved folder hierarchy key, primary calendar server ID, and collection `SyncKey`;
- phase (`disabled`, `idle`, `queued`, protocol discovery, folder discovery, downloading, applying, cancelling, or blocked);
- trigger and logical run identity, consecutive transient attempts, last successful completion, and stable problem category.

The profile repository remains the credential boundary. A separate synchronization-state repository uses the same singleton Preferences DataStore and namespaced keys; creating a second DataStore for the same file is forbidden. All multi-field transitions use one DataStore transaction. No server event payload or certificate bytes are stored.

Every queued worker carries the generation it was created for. The executor re-reads the active generation and `enabled` value before network access, before each Calendar Provider batch, and before each checkpoint commit. A mismatch returns an obsolete/cancelled outcome without side effects. Profile replacement, disable, and explicit cancellation therefore do not depend solely on cooperative WorkManager cancellation.

Alternative: identify work only by WorkManager IDs and cancellation. Rejected because cancellation is asynchronous and an already running worker can otherwise write after a profile switch or cleanup.

### 3. Treat profile activation as an ordered post-persistence lifecycle

The existing validate-probe-commit flow remains authoritative for profile safety. After commit, Save compares the new profile to the previously persisted profile. A first or changed profile runs this ordered lifecycle:

1. atomically set `enabled=true`, increment the generation, mark full sync required, and clear protocol checkpoints;
2. cancel obsolete execution and periodic work and clear the old generation's problem notification;
3. verify calendar permission; when available, delete only the owned calendar row, which cascades its events and child rows;
4. register unique periodic trigger work and unique immediate execution work.

If post-commit permission, cleanup, or scheduling fails, the verified profile is not rolled back. The synchronization state becomes blocked and the UI exposes the corrective action. A connection-check failure or manual recheck never enters this lifecycle.

Disable uses the same fence in the opposite direction: atomically set `enabled=false`, increment the generation, and clear checkpoints before cancelling work and deleting the owned calendar. If permission has been revoked, cleanup remains a visible pending problem but no network synchronization is re-enabled. Enable requests permission, creates another generation, marks a full sync, deletes any owned-calendar remainder, and restores immediate and periodic work.

Alternative: clear the calendar before committing a changed profile. Rejected because a failed connection check would destroy a still-valid previous mirror. Alternative: roll the profile back when scheduling fails. Rejected because profile persistence and Android scheduling/provider writes cannot form one transaction and rollback would misrepresent a successfully verified configuration.

### 4. Use WorkManager as a trigger plus bounded unique execution chain

Add pinned AndroidX WorkManager runtime support. Exactly one `PeriodicWorkRequest` runs at the 15-minute minimum with connected-network constraint. The periodic worker is lightweight: it validates `enabled` and generation and requests the same unique one-time execution used by Save, Enable, and Sync Now. It does not mutate the calendar itself. Unique-work policy and the generation fence ensure that immediate and periodic triggers cannot create concurrent executors.

The execution worker processes protocol work in bounded slices. Within a slice it can resolve capabilities/folders and apply multiple response pages, but it stops at a soft elapsed-time/page budget well below Android's normal worker limit. If `MoreAvailable` remains, it commits the last completed page, records queued continuation state, appends one continuation for the same generation, and finishes successfully. This avoids a foreground service and preserves progress for calendars larger than one worker budget.

The execution gate is also represented in durable state so reconstructed workers converge on one logical run. A periodic trigger received during active execution is coalesced. Sync Now is disabled while queued/running. Cancellation marks the logical run as cancelling, cancels its unique chain, and increments a run token without changing the profile generation or periodic work; checkpoints from completed pages remain valid.

Retryable failures return a WorkManager retry outcome only while `runAttemptCount < 5`, using exponential backoff beginning at 30 seconds. On the fifth failure the executor records the persistent problem and completes that invocation without cancelling periodic work. Non-retryable outcomes record blocked state and complete without a delay-only loop. A later periodic trigger may retry from the same committed checkpoint; success clears the count and problem.

Alternatives considered:

- A custom SyncAdapter/AccountManager account offers system sync settings but adds an account surface and plumbing not needed for one local profile.
- A direct `dataSync` foreground service or long-running WorkManager worker offers one continuous run but is subject to Android 16 runtime quotas and mandatory foreground notification behavior.
- One unbounded normal worker is simpler but cannot guarantee completion for all server-returned history.

### 5. Share secure endpoint resolution and add a minimal ActiveSync command client

Refactor the existing verifier only enough to share client-credential resolution, composite trust, HTTP client construction, HTTPS redirect policy, endpoint diagnostics, and failure classification. Feature code continues to see core ports rather than OkHttp or KeyChain types.

A full run performs `OPTIONS` against the configured endpoint, follows the existing safe redirect policy, selects the highest mutually supported version from 14.0, 14.1, 16.0, and 16.1, and retains the terminal command endpoint for that run. A server that offers only 12.1 is rejected during Save or recheck because it cannot provide the `ResponseType` contract needed to classify pending meetings reliably. Command requests use plain-text ActiveSync query parameters with percent encoding, `domain\login` as `User`, a stable random alphanumeric `DeviceId`, constant `DeviceType`, `MS-ASProtocolVersion`, and `application/vnd.ms-sync.wbxml`. The mTLS identity and server trust rules are identical to connection verification. No Basic/OAuth secret is introduced.

Implement the minimum WBXML 1.3 encoder/decoder and code pages needed for `FolderHierarchy`, `AirSync`, `Calendar`, and `AirSyncBase`. Calendar decoding includes `MeetingStatus`, `ResponseType`, `ResponseRequested`, attendee response, and the corresponding exception fields. The decoder validates header/string-table bounds, page switches, token structure, required values, and maximum body/nesting/item limits; it skips well-formed unknown elements but never silently substitutes malformed identity, time, recurrence, response state, or synchronization keys. Unit fixtures include canonical encoded requests and representative server responses rather than relying only on round trips through the same codec.

Full synchronization performs initial `FolderSync(0)`, selects the default Calendar folder, obtains an initial collection key with `SyncKey=0`, and requests unfiltered changes with a requested window of 100 until `MoreAvailable` is absent. Incremental runs first reconcile the retained folder hierarchy key, then use the retained collection key. Invalid folder/collection keys consume the run's one automatic full-reset allowance; a second invalidation is blocked.

For ActiveSync 14.0 and 14.1, the request declares the supported calendar properties needed to control ghosted fields, including meeting response properties. For 16.0 and 16.1, omitted top-level fields are merged according to protocol ghosting semantics. Pending invitations are sourced only from meeting items in the primary Calendar `Sync`; no Inbox collection is discovered or synchronized. The client requests a bounded plain-text body representation and does not implement multipart responses. A server response requiring `Provision` is classified as unsupported policy rather than accepted.

Alternative: adopt a third-party ActiveSync client. Rejected because no existing dependency is part of the pinned trust boundary, most clients pull in account/bidirectional behavior, and the required read-only command surface is small enough to test directly.

### 6. Model server events independently, then map them to one local calendar

The WBXML layer produces core calendar item models before Android mapping. Models distinguish field absence from an explicitly empty field so incremental ghosting, response-state retention, and reminder removal are correct. Dedicated pure mappers handle ActiveSync date-time strings, all-day date boundaries, Windows time-zone blobs, recurrence patterns, exceptions, attendees, meeting/response state, availability, sensitivity, body, and reminder offsets.

Meeting presentation uses `MeetingStatus` to distinguish organizer-owned and received meetings, then `ResponseType` as the authoritative user response. If a received meeting omits `ResponseType`, an unambiguous attendee row matching the profile email is the fallback; if neither signal can classify the response, the page is rejected rather than silently shown as accepted.

| ActiveSync response | Android event status | Self attendee | Availability | Color |
|---|---|---|---|---|
| None or Not Responded | Tentative | Invited | Tentative | Pale override |
| Tentative | Tentative | Tentative | Tentative | Pale override |
| Accepted | Confirmed | Accepted | Server-mapped | Calendar color |
| Current user is organizer | Confirmed | Organizer | Server-mapped | Calendar color |
| Declined but still returned | Cancelled | Declined | Server-mapped | No pale override |

The pale override is an opaque sRGB `EVENT_COLOR` derived independently per channel from the owned calendar color as `round(c + (255 - c) * 0.45)`. Blending toward white both lowers saturation and raises lightness while remaining deterministic in JVM tests. Accepted and organizer-owned updates set the semantic fields and remove `EVENT_COLOR` in the same provider batch, so the existing event identity immediately returns to the normal calendar color. A later transition back to pending or tentative restores the override.

Recurring exceptions carry an optional response-state override. An absent exception response inherits the series response; an explicit response remaps only that exception row, including its event color. Partial ActiveSync changes preserve the prior series or exception response until the server supplies a replacement.

The local adapter creates a calendar through sync-adapter-qualified Calendar Provider URIs with:

- a package-specific constant account name;
- `CalendarContract.ACCOUNT_TYPE_LOCAL`;
- a constant internal calendar name distinct from its user-visible display name;
- `CAL_ACCESS_READ`, visible/sync-events flags, supported reminder type, and profile email as display/owner metadata only.

Ownership resolution always includes account name, account type, and internal name; after finding a row it verifies those markers before using its ID. Display name and email are never ownership selectors. Multiple matching owned rows are treated as corruption and reconciled by deleting only rows that match the complete application identity before recreating one.

Master events use ActiveSync `ServerId` as `_SYNC_ID`; Exchange UID is stored separately when available. Changes use upsert by owned calendar plus `_SYNC_ID`, replace affected attendee/reminder rows, and represent recurrence exceptions with the provider's original-sync/original-instance fields. Deletions are sync-adapter deletes so they remove rows instead of marking outbound dirty state. The adapter never scans or mutates another calendar.

Each ActiveSync response page becomes one `ContentProviderOperation` batch. If a window of 100 exceeds Binder/provider transaction limits, the failed batch leaves the checkpoint unchanged; the executor re-requests the same key with a smaller window, down to one item, rather than splitting an already accepted page into non-atomic commits.

Calendar Provider and DataStore cannot share a transaction. The chosen commit order is calendar batch first, synchronization key second. A crash between them replays the page. Upserts, child replacement, and deletes are therefore idempotent. The opposite order is rejected because committing the key first could permanently skip local events after a crash.

### 7. Centralize permission, failure, and notification policy

Calendar access is a hard precondition checked before any synchronization network request, because downloading a page that cannot be committed only consumes server/device resources. `MainActivity` owns Activity-result launchers and forwards results to core lifecycle use cases; the ViewModel emits permission intents without accessing infrastructure.

Notification permission is independent. Denial is persisted as presentation state but does not block synchronization. The notification adapter uses one stable channel and notification ID, an ongoing problem notification, immutable/update-current pending intent, and localized categories. It never includes endpoint, login, response text, event content, or certificate data. Success, disable, or generation replacement cancels the notification.

The core classifier distinguishes:

- transient: connectivity, DNS, timeout, I/O, 408, 429, 5xx, and Android interruption;
- resettable once: invalid folder or collection key and changed primary folder;
- blocked/critical: KeyChain identity, TLS/trust/hostname/mTLS, authentication/access, redirect policy, compatibility/provisioning, missing primary folder, repeated key invalidation, malformed required protocol/calendar data, revoked calendar permission, and permanent provider failure.

Existing connection failure categories are reused where their meaning matches. New categories remain stable presentation values, not exception strings.

### 8. Extend the existing settings screen instead of adding a navigation surface

The current screen remains the single configuration and status surface. Its ViewModel combines loaded profile state with the durable synchronization state and exposes:

- phase and current full/incremental stage;
- last successful completion;
- actionable problem and permission actions;
- Sync Now when enabled and idle;
- Cancel when queued/running;
- Disable when enabled;
- Enable when disabled with a saved profile.

Connection Save remains mutually exclusive with recheck and editing as today. Post-save synchronization is not tied to Save's coroutine: Save reports verified persistence, then the durable lifecycle state shows queued/running/blocked progress. Certificate diagnostics retain their existing ephemeral semantics.

### 9. Test protocol and policy below Android adapters

Implementation follows RED-GREEN-REFACTOR. JVM tests cover:

- generation fencing, activation ordering, enable/disable, cancellation, trigger coalescing, bounded continuation, retry budget, and state restoration;
- WBXML primitives, code-page switching, size/depth limits, command/status fixtures, folder selection, pagination, ghosted changes, invalid-key recovery, and redirect/transport reuse;
- time-zone, all-day, recurrence, exception, attendee, `MeetingStatus`/`ResponseType` classification, pending/tentative/accepted/organizer/declined transitions, pale-color derivation, sensitivity, body, and reminder mapping;
- provider operation planning, complete ownership predicates, idempotent replay, adaptive window reduction, and checkpoint commit ordering through fakes;
- permission gating, safe failure classification, notification deduplication/clear policy, ViewModel controls, and localized resources.

Android-facing workers, ContentResolver calls, permission launchers, notification posting, and KeyChain remain thin adapters verified by compilation, Lint, debug assembly, and a documented manual Android 16 scenario. No `src/androidTest`, emulator, Robolectric, or end-to-end suite is introduced.

## Risks / Trade-offs

- **[WorkManager timing is inexact]** → Display a 15-minute requested interval, never a guaranteed next-run time, and retain manual Sync Now.
- **[A complete initial history can exceed one job budget]** → Use bounded page slices, durable checkpoints, and unique continuations instead of a long-running foreground worker.
- **[Calendar Provider and DataStore are not atomic together]** → Commit idempotent provider batches before keys and test page replay after every boundary.
- **[Binder limits vary with event/attendee size]** → Start with window 100 and retry the unchanged key with progressively smaller server windows after transaction-size failure.
- **[Windows-to-IANA time-zone mapping can be ambiguous]** → Implement and fixture-test the ActiveSync time-zone structure, prefer exact Android IDs when resolvable, preserve event instants, and block malformed structures rather than silently shifting recurrence.
- **[Calendar apps can ignore per-event color or render tentative state differently]** → Write both the semantic tentative/invited fields and `EVENT_COLOR`, assert Calendar Provider `DISPLAY_COLOR`, and manually verify that the target Android 16 calendar UI renders pending events paler; document any UI-specific limitation without weakening the stored semantics.
- **[Raising the protocol floor excludes ActiveSync 12.1-only servers]** → Reject them during the existing capability check with a specific compatibility error instead of guessing meeting response state.
- **[Cancellation is cooperative]** → Fence every side-effect boundary with generation/run tokens so late completion cannot escape cancellation semantics.
- **[Notification permission can be denied]** → Keep the same persistent problem in the app and expose a system-settings action; do not pretend delivery occurred.
- **[Server requires ActiveSync provisioning or unsupported extensions]** → Fail with a specific compatibility problem and keep policy/device-management behavior out of this change.
- **[DataStore schema grows]** → Namespace keys, use transactional defaults, test corrupt/missing values, and never instantiate multiple stores for the same file.

## Migration Plan

1. Add pinned WorkManager dependencies, permissions, notification channel resources, and the manual worker factory without enabling work by default.
2. Add synchronization state with safe defaults. An installation upgrading with an existing verified profile but no synchronization metadata initializes as disabled and requires the user to select Enable so runtime permissions can be requested in the foreground.
3. Add core lifecycle/protocol policies and infrastructure adapters behind those disabled defaults.
4. Connect post-Save activation, permission results, settings controls, and worker scheduling only after all unit suites pass.
5. Run `test`, `lintDebug`, `verifyBootstrap`, OpenSpec verification, diff review, and the manual Android 16 permission/background/calendar-isolation checklist, including a pending-to-accepted meeting transition and observed pale system-calendar rendering, before archive.

Rollback during development is to disable synchronization, which invalidates work and removes the owned calendar, before reverting the feature. Older builds ignore the additional namespaced DataStore keys. Because distribution is local, an installed build must not be downgraded while its owned calendar is active without first using Disable or manually removing that application-owned local calendar.
