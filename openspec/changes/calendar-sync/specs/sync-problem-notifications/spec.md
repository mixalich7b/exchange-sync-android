## Purpose

Defines permission gating and safe persistent notification of background calendar synchronization failures that require user action or have exhausted automatic recovery.

## ADDED Requirements

### Requirement: Calendar access permission gate
The application SHALL request Android calendar read and write permissions when a successfully saved or re-enabled profile first needs calendar access. Without both permissions it SHALL keep the verified profile, perform no synchronization network request or Calendar Provider mutation, expose a blocked state with a grant-access action, and resume the pending full synchronization after access is granted.

#### Scenario: Calendar permission is granted
- **WHEN** an enabled verified profile needs calendar access and the user grants the required permissions
- **THEN** the pending full synchronization and periodic scheduling can proceed without another profile save

#### Scenario: Calendar permission is denied
- **WHEN** the user denies either required calendar permission
- **THEN** the profile remains saved, synchronization is blocked before network or provider access, and the UI explains how to grant access

#### Scenario: Calendar permission is revoked in the background
- **WHEN** a background run discovers that previously granted calendar access is no longer available
- **THEN** it stops without advancing synchronization state and records a critical permission problem requiring user action

### Requirement: Notification permission does not block synchronization
The application SHALL request Android notification permission before relying on background problem notifications. Denial SHALL NOT disable otherwise permitted calendar synchronization, but the UI SHALL state that critical background alerts cannot be displayed and SHALL provide a way to open the relevant system settings.

#### Scenario: Notification permission is granted
- **WHEN** a critical or persistent synchronization problem exists and notification permission is available
- **THEN** the application can display the synchronization-problem notification

#### Scenario: Notification permission is denied
- **WHEN** the user denies or revokes notification permission
- **THEN** synchronization continues according to calendar and network state, no prohibited notification is posted, and the persisted in-app problem remains visible

### Requirement: Critical synchronization problem classification
The application SHALL classify an unavailable client-certificate alias, mTLS or server-trust failure, authentication or access rejection, unsafe redirect, incompatible endpoint or protocol, unsupported required provisioning, missing primary calendar, repeated invalid synchronization key, malformed required WBXML or calendar data, revoked calendar access, and permanent Calendar Provider failure as immediately user-actionable. It SHALL classify a retryable failure as user-actionable after its five-attempt run budget is exhausted.

#### Scenario: Immediate user action is required
- **WHEN** background synchronization encounters a non-retryable classified problem
- **THEN** it records a stable actionable category and requests user notification without entering a delay-only retry loop

#### Scenario: Transient failures exhaust retries
- **WHEN** a logical run reaches its fifth consecutive transient failure
- **THEN** it records a persistent availability problem and requests user notification while preserving future periodic attempts

#### Scenario: Invalid key recovers once
- **WHEN** a previously valid synchronization key is rejected and the automatic full reset succeeds
- **THEN** no critical notification is shown for that recovered condition

### Requirement: Persistent safe synchronization notification
When notification permission is available, the application SHALL display at most one ongoing synchronization-problem notification for the active profile generation. The notification SHALL identify a localized safe problem category, state that calendar synchronization needs attention, and open the settings screen; it SHALL NOT expose the server hostname, profile login, response body, exception text, certificate material, event contents, or other secrets.

#### Scenario: Repeated background runs report the same problem
- **WHEN** multiple attempts or periodic runs encounter a problem in the same active generation
- **THEN** they update or retain one notification instead of creating duplicates

#### Scenario: User opens the problem notification
- **WHEN** the user selects the notification
- **THEN** the application opens its settings UI with the current persisted synchronization problem and an applicable corrective action

#### Scenario: Synchronization recovers
- **WHEN** the active generation later completes synchronization successfully
- **THEN** the persisted problem and its notification are cleared

#### Scenario: Profile generation changes or synchronization is disabled
- **WHEN** a replacement profile becomes active or the user disables synchronization
- **THEN** the obsolete generation's problem notification is removed

