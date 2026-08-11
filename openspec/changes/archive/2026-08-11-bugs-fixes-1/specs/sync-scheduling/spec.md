## MODIFIED Requirements

### Requirement: Serialized immediate synchronization
The settings UI SHALL allow the user to request synchronization now when an enabled saved profile exists, no synchronization is active, and the durable phase is either idle or blocked. Manual, profile-triggered, re-enabled, retry, and periodic triggers SHALL converge on one serialized execution path so two runs cannot mutate the calendar concurrently. A manual request from blocked state SHALL preserve committed calendar data and checkpoints, clear the prior terminal presentation for the new attempt, and retain a pending full reset only when one was already required.

#### Scenario: User starts synchronization now
- **WHEN** synchronization is enabled, a saved profile exists, and no run is active
- **THEN** the application queues an immediate incremental synchronization or a full synchronization when a full reset is pending

#### Scenario: User retries blocked synchronization
- **WHEN** an enabled synchronization is blocked by a non-active terminal problem and the user selects Sync now
- **THEN** the application queues one new serialized attempt from the last committed checkpoint and makes the control unavailable again while that attempt is active

#### Scenario: Retried problem persists
- **WHEN** a manual attempt from blocked state encounters the same non-retryable problem again
- **THEN** the application returns to blocked state with the actionable problem and leaves the control available for a later user attempt

#### Scenario: Trigger arrives during active synchronization
- **WHEN** another manual or periodic trigger arrives while the current generation is already queued or running
- **THEN** no concurrent synchronization starts and at most one follow-up need is retained

#### Scenario: No saved profile exists
- **WHEN** the user has not saved a verified profile
- **THEN** immediate and periodic synchronization controls remain unavailable

### Requirement: Disable and re-enable synchronization
The settings UI SHALL let the user disable synchronization without deleting the saved profile. Disable SHALL first invalidate active work, stop immediate and periodic synchronization, remove the entire application-owned calendar with its events and reminders, and clear protocol checkpoints. Re-enable SHALL keep the profile, create a new generation, request a full synchronization, and restore periodic scheduling. If owned-calendar cleanup fails after disablement, synchronization SHALL remain disabled, the cleanup intent and actionable problem SHALL remain durable, and the UI SHALL continue to offer cleanup retry without enabling network synchronization.

#### Scenario: User disables synchronization
- **WHEN** synchronization is enabled or active and the user selects Disable
- **THEN** active and scheduled synchronization is invalidated, the owned calendar and checkpoints are removed, the profile remains saved, and the durable state becomes disabled

#### Scenario: Disable cleanup fails
- **WHEN** synchronization has been made disabled but Calendar Provider cannot delete the application-owned calendar
- **THEN** no network work is restored, cleanup remains pending with an actionable problem, and the user can request cleanup again

#### Scenario: Pending cleanup succeeds on retry
- **WHEN** the disabled state has pending cleanup and a startup reconciliation, permission recovery, or user cleanup retry can access Calendar Provider successfully
- **THEN** the owned calendar is removed, the pending cleanup problem is cleared, and synchronization remains disabled

#### Scenario: User re-enables synchronization
- **WHEN** a verified profile remains saved in disabled state with no cleanup operation in progress and the user selects Enable
- **THEN** the application requests required access, creates a new generation, queues a full synchronization, and registers one periodic request

#### Scenario: Application restarts while disabled
- **WHEN** the process is recreated after synchronization was disabled
- **THEN** the profile remains available, no calendar synchronization is scheduled, pending owned-calendar cleanup is reconciled when necessary, and the UI continues to offer only actions valid for the resulting disabled state
