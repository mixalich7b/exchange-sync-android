# sync-scheduling Specification

## Purpose

Defines the durable user-controlled lifecycle for immediate and periodic calendar synchronization, including reset, serialization, cancellation, disablement, resumption, retry, and background progress reporting.

## Requirements

### Requirement: Profile-bound synchronization lifecycle
Synchronization SHALL be bound to a monotonically changing profile generation. A successfully saved new or changed profile SHALL invalidate all work and checkpoints from the previous generation, enable synchronization, clear only the application-owned calendar, and request a new full synchronization. Work from an obsolete generation SHALL NOT write calendar data or commit synchronization state.

#### Scenario: First profile is saved
- **WHEN** a first profile passes verification and is persisted
- **THEN** synchronization becomes enabled, a full synchronization is requested, and periodic synchronization is registered for that profile generation

#### Scenario: Existing profile changes
- **WHEN** any saved profile field or selected certificate alias is successfully replaced
- **THEN** previous work is invalidated, the owned calendar and checkpoints are reset, and the replacement profile starts with a full synchronization

#### Scenario: Obsolete work finishes late
- **WHEN** cancelled work from a previous profile generation reaches a calendar or checkpoint boundary after a new generation is active
- **THEN** it performs no write for the obsolete generation

#### Scenario: Manual connection recheck completes
- **WHEN** the user only rechecks an unchanged saved profile
- **THEN** no synchronization generation changes, no calendar is cleared, and no new synchronization is requested as a side effect of the recheck

### Requirement: Background-independent synchronization execution
Synchronization SHALL be owned by Android persistent background work rather than by an activity, composition, screen, or ViewModel lifecycle. Minimizing or closing the application UI SHALL NOT cancel active or scheduled synchronization, and an interrupted resumable synchronization SHALL remain eligible to continue from its last committed checkpoint.

#### Scenario: Application is minimized during synchronization
- **WHEN** the settings activity leaves the foreground while a synchronization is running
- **THEN** the synchronization remains eligible to continue and its durable progress is available when the UI is reopened

#### Scenario: Application process is stopped
- **WHEN** Android stops the process before a synchronization completes
- **THEN** persistent background work can recreate the process and resume from the last committed page without relying on the prior UI instance

### Requirement: Best-effort fifteen-minute periodic synchronization
While synchronization is enabled and a verified profile exists, the application SHALL register exactly one network-constrained periodic synchronization with a 15-minute repeat interval. The UI SHALL NOT claim exact execution times because Android battery, quota, and network constraints can delay a run.

#### Scenario: Synchronization is enabled
- **WHEN** an enabled verified profile has completed lifecycle setup
- **THEN** one periodic synchronization request exists with a 15-minute interval and a requirement for connected networking

#### Scenario: Android delays periodic work
- **WHEN** Doze, battery optimization, job quota, or missing network postpones the nominal interval
- **THEN** the application waits for Android to run the work and does not use an exact alarm, polling service, or built-in Exchange integration to bypass the delay

#### Scenario: Lifecycle setup is repeated
- **WHEN** the application reconciles scheduling more than once for the same enabled generation
- **THEN** it retains one logical periodic request rather than accumulating duplicate schedules

### Requirement: Serialized immediate synchronization
The settings UI SHALL allow the user to request synchronization now when an enabled saved profile exists and no synchronization is active. Manual, profile-triggered, re-enabled, retry, and periodic triggers SHALL converge on one serialized execution path so two runs cannot mutate the calendar concurrently.

#### Scenario: User starts synchronization now
- **WHEN** synchronization is enabled, a saved profile exists, and no run is active
- **THEN** the application queues an immediate incremental synchronization or a full synchronization when a full reset is pending

#### Scenario: Trigger arrives during active synchronization
- **WHEN** another manual or periodic trigger arrives while the current generation is already queued or running
- **THEN** no concurrent synchronization starts and at most one follow-up need is retained

#### Scenario: No saved profile exists
- **WHEN** the user has not saved a verified profile
- **THEN** immediate and periodic synchronization controls remain unavailable

### Requirement: Cooperative in-progress cancellation
The settings UI SHALL expose cancellation only while synchronization is queued or running. Cancellation SHALL stop further network pages and calendar batches for the current attempt, preserve the last fully committed checkpoint and calendar contents, and leave synchronization enabled with its periodic schedule intact.

#### Scenario: User cancels a running synchronization
- **WHEN** the user selects Cancel while a run is in progress
- **THEN** the run enters cancelling state, performs no work beyond its current atomic boundary, and returns to an enabled non-running state without clearing the calendar

#### Scenario: Cancellation occurs between pages
- **WHEN** cancellation is observed after one page and its checkpoint have committed
- **THEN** that page remains visible and the next run resumes from its committed synchronization key

#### Scenario: No synchronization is active
- **WHEN** synchronization is idle, disabled, blocked, or already cancelled
- **THEN** the cancellation control is unavailable and no state is changed

### Requirement: Disable and re-enable synchronization
The settings UI SHALL let the user disable synchronization without deleting the saved profile. Disable SHALL first invalidate active work, stop immediate and periodic synchronization, remove the entire application-owned calendar with its events and reminders, and clear protocol checkpoints. Re-enable SHALL keep the profile, create a new generation, request a full synchronization, and restore periodic scheduling.

#### Scenario: User disables synchronization
- **WHEN** synchronization is enabled or active and the user selects Disable
- **THEN** active and scheduled synchronization is invalidated, the owned calendar and checkpoints are removed, the profile remains saved, and the durable state becomes disabled

#### Scenario: User re-enables synchronization
- **WHEN** a verified profile remains saved in disabled state and the user selects Enable
- **THEN** the application requests required access, creates a new generation, queues a full synchronization, and registers one periodic request

#### Scenario: Application restarts while disabled
- **WHEN** the process is recreated after synchronization was disabled
- **THEN** the profile remains available, no calendar synchronization is scheduled, and the UI continues to offer Enable

### Requirement: Retry and exponential backoff
Transient network, timeout, DNS, I/O, HTTP 408, HTTP 429, HTTP 5xx, and Android-interruption failures SHALL preserve the committed checkpoint and retry the same logical run with exponential backoff beginning at 30 seconds. A run SHALL stop retrying after five consecutive transient failures, record a persistent problem, and leave future periodic synchronization eligible to try again.

#### Scenario: Transient failure occurs
- **WHEN** a run encounters a retryable failure before five consecutive failed attempts
- **THEN** it advances no uncommitted checkpoint and becomes eligible for another attempt after an exponentially increasing delay

#### Scenario: Retry later succeeds
- **WHEN** a backed-off retry successfully completes synchronization
- **THEN** the consecutive failure count is cleared and normal periodic scheduling continues

#### Scenario: Retry budget is exhausted
- **WHEN** the fifth consecutive attempt for one logical run ends in a retryable failure
- **THEN** that run stops retrying, records a persistent synchronization problem, and does not cancel the next periodic opportunity

#### Scenario: Non-retryable failure occurs
- **WHEN** a run encounters a credential, permission, TLS, access, compatibility, persistent protocol-data, or local-provider failure that cannot recover through delay alone
- **THEN** it performs no automatic backoff loop and records a blocked user-actionable problem

### Requirement: Durable synchronization presentation state
The application SHALL persist whether synchronization is enabled, its generation, pending full-reset need, current logical phase, cancellation intent, consecutive failure count, last successful completion time, and current actionable failure independently of the settings UI. It SHALL expose controls consistently with that state and SHALL NOT persist raw server bodies, exception stack traces, certificate encodings, private keys, or client secrets.

#### Scenario: UI opens during active work
- **WHEN** the settings screen is created while a synchronization is queued, running, cancelling, blocked, disabled, or idle
- **THEN** it displays the recovered state, current phase, last success when available, and only the controls valid for that state

#### Scenario: Synchronization completes successfully
- **WHEN** a run reaches the end of all available changes for the active generation
- **THEN** the state becomes idle and enabled, the completion time is updated, and prior synchronization failure state is cleared
