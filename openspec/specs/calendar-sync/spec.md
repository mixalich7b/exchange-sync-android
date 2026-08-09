# calendar-sync Specification

## Purpose

Defines the one-way Exchange ActiveSync mirror that discovers the configured mailbox's primary calendar and represents its complete server-returned contents in one isolated, read-only Android calendar.

## Requirements

### Requirement: Primary ActiveSync calendar discovery
The application SHALL use the saved profile's HTTPS endpoint, selected mTLS identity, combined system-plus-local server trust, and highest mutually supported ActiveSync version from 14.0, 14.1, 16.0, and 16.1 to synchronize only the mailbox's primary Calendar collection. It SHALL identify that collection from the hierarchy returned by `FolderSync` and SHALL NOT synchronize secondary, shared, resource, task, contact, mail, or Inbox meeting-request collections.

#### Scenario: Primary calendar is discovered
- **WHEN** `FolderSync` succeeds and identifies the mailbox's primary Calendar collection
- **THEN** the application retains that collection identifier for calendar synchronization and ignores every other collection

#### Scenario: Primary calendar is unavailable
- **WHEN** the hierarchy contains no unambiguous primary Calendar collection
- **THEN** the application writes no calendar events, advances no calendar synchronization key, and reports a user-actionable compatibility problem

#### Scenario: Server supports only ActiveSync 12.1
- **WHEN** the server advertises ActiveSync 12.1 but none of 14.0, 14.1, 16.0, or 16.1
- **THEN** the application reports an ActiveSync compatibility error because reliable meeting response state is unavailable

#### Scenario: ActiveSync endpoint redirects
- **WHEN** an ActiveSync capability or command request receives a permitted HTTPS redirect
- **THEN** the application applies the configured mTLS identity and normal hostname and chain validation at every destination and rejects HTTP downgrade, malformed, cyclic, or excessive redirect chains

#### Scenario: Server requires unsupported provisioning
- **WHEN** the server refuses calendar commands until an ActiveSync device policy is provisioned
- **THEN** the application writes no calendar changes and reports that the server policy is unsupported instead of registering a system Exchange account or accepting device-management policy

### Requirement: Complete initial calendar synchronization
The application SHALL begin a full collection synchronization by priming the collection with an ActiveSync `SyncKey` of `0` without `GetChanges`, then use the synchronization key returned by that successful response to request changes without a past or future date filter. It SHALL continue requesting pages until the server reports no more available changes and retain all history and all future events returned by the server rather than imposing an application time horizon.

#### Scenario: Full synchronization primes the collection key
- **WHEN** a full synchronization has selected the primary Calendar collection
- **THEN** the application first sends `SyncKey=0` without `GetChanges`, persists no calendar page from that priming response, and sends unfiltered `GetChanges` only with the nonzero key returned by the server

#### Scenario: Full synchronization spans multiple pages
- **WHEN** the server returns one or more pages marked as having more changes available
- **THEN** the application applies every page in order and continues with the returned synchronization key until the complete collection has been received

#### Scenario: Server returns historical and future events
- **WHEN** an unfiltered full synchronization returns events before and after the current time
- **THEN** the owned Android calendar contains both the historical and future events

#### Scenario: Server limits retained history
- **WHEN** the server returns only a subset of historical events despite an unfiltered request
- **THEN** the application mirrors the complete subset returned by the server and does not manufacture or fetch events through a different protocol

### Requirement: Incremental one-way calendar mirror
After a complete full synchronization, the application SHALL use the last committed collection synchronization key to apply server additions, changes, deletions, and soft deletions. It SHALL send no client calendar `Add`, `Change`, `Delete`, meeting response, or other calendar mutation to Exchange.

#### Scenario: Server adds an event
- **WHEN** an incremental `Sync` response adds a previously unknown server event
- **THEN** the application creates the corresponding event and child data in its owned Android calendar

#### Scenario: Server changes an event
- **WHEN** an incremental `Sync` response changes a known server event
- **THEN** the application updates that event and its affected child data without creating a duplicate

#### Scenario: Partial server change omits unchanged properties
- **WHEN** the selected ActiveSync version defines omitted properties in a change as unchanged
- **THEN** the application preserves the previously synchronized values of those properties

#### Scenario: Server deletes an event
- **WHEN** an incremental response deletes or soft-deletes a known server event
- **THEN** the application removes that event and its associated reminders, attendees, and recurrence exceptions from its owned calendar

#### Scenario: Local event mutation is observed
- **WHEN** another local component modifies a synchronized event despite the calendar's read-only declaration
- **THEN** the application never uploads the mutation and a later server change or full synchronization restores the server representation

### Requirement: Calendar event fidelity
The application SHALL map each supported Exchange calendar item to Android Calendar Provider data using the server identifier as a stable synchronization identity. The representation SHALL preserve the available UID, subject, body, location, start and end, all-day flag, time zone, recurrence, deleted and changed exceptions, organizer, attendees, `MeetingStatus`, `ResponseType`, `ResponseRequested`, current-user attendee response, availability, sensitivity, event status, and server reminder without exposing unparsed server payloads. Meetings returned by the primary Calendar collection with `ResponseType` None, Not Responded, or Tentative SHALL remain in the owned Android calendar and SHALL be visually distinguished from accepted or organizer-owned meetings.

#### Scenario: Ordinary timed event is synchronized
- **WHEN** the server returns a non-recurring timed event with descriptive, organizer, attendee, availability, sensitivity, and status properties
- **THEN** the owned Android event exposes the corresponding supported Calendar Provider values with the correct start, end, and time zone

#### Scenario: Pending meeting invitation is synchronized
- **WHEN** the primary Calendar `Sync` response returns an active received meeting with `ResponseType` None or Not Responded
- **THEN** the application creates or updates the same owned-calendar event with tentative event status, tentative availability, current-user attendee status Invited, and a paler event-specific color

#### Scenario: Tentatively accepted meeting is synchronized
- **WHEN** the primary Calendar `Sync` response returns an active received meeting with `ResponseType` Tentative
- **THEN** the application creates or updates the same owned-calendar event with tentative event status, tentative availability, current-user attendee status Tentative, and the paler event-specific color

#### Scenario: Pending event color is derived
- **WHEN** a pending or tentatively accepted meeting is mapped from an owned calendar whose opaque color has red, green, and blue channel values `c`
- **THEN** its opaque event-specific color uses `round(c + (255 - c) * 0.45)` for each channel so Calendar Provider exposes a deterministic color that is less saturated and lighter than the calendar color

#### Scenario: Meeting becomes accepted
- **WHEN** an incremental response changes an existing pending or tentative meeting to `ResponseType` Accepted
- **THEN** the application updates the same event identity without duplication, sets confirmed event and current-user Accepted status, restores server availability, and removes the event-specific color so the normal calendar color is used

#### Scenario: Current user organizes the meeting
- **WHEN** `MeetingStatus` identifies the current user as organizer
- **THEN** the event is confirmed, uses the server availability and normal calendar color, and is not presented as a pending invitation

#### Scenario: Meeting is declined
- **WHEN** the primary Calendar continues returning a received meeting with `ResponseType` Declined
- **THEN** the application retains that server-returned identity with current-user Declined and cancelled event status and does not apply the pending color

#### Scenario: ResponseType is omitted from a received meeting
- **WHEN** a received active meeting omits `ResponseType` but contains an unambiguous current-user attendee response
- **THEN** the application derives the pending, tentative, accepted, or declined presentation from that attendee response

#### Scenario: Received meeting response cannot be classified
- **WHEN** a received active meeting contains neither `ResponseType` nor an unambiguous current-user attendee response
- **THEN** the application does not commit that response page or its synchronization key and reports a user-actionable protocol-data problem instead of silently presenting an accepted state

#### Scenario: All-day event is synchronized
- **WHEN** the server returns an all-day event
- **THEN** the Android event is represented as all-day with date boundaries that do not shift when the device display time zone changes

#### Scenario: Recurring series has exceptions
- **WHEN** the server returns a recurring series with changed and deleted occurrences
- **THEN** the Android calendar expands the series according to the recurrence and reflects each changed or removed occurrence without duplicating the series

#### Scenario: Recurring meeting response applies to exceptions
- **WHEN** a recurring meeting has a pending, tentative, accepted, or declined series response and an exception omits its own response
- **THEN** the exception inherits the series response presentation, while an explicit exception response overrides it for that occurrence

#### Scenario: Event has a reminder
- **WHEN** the server returns a supported reminder offset for an event
- **THEN** the application creates the corresponding Android alert reminder for that event

#### Scenario: Event has no reminder
- **WHEN** the server omits or disables the reminder for an event
- **THEN** the synchronized Android event has no reminder left from an earlier version

#### Scenario: Required event data is malformed
- **WHEN** a server item cannot be deterministically decoded or mapped without corrupting its identity, time, or recurrence
- **THEN** the application does not commit that response page or its next synchronization key and reports a user-actionable protocol-data problem

### Requirement: Isolated read-only Android calendar ownership
The application SHALL create at most one visible local Android calendar with a stable application-specific account identity and read-only access. Every query, insert, update, clear, and delete SHALL be scoped to that owned identity and, once resolved, its provider calendar identifier; no operation SHALL select another calendar merely by display name, owner email, or profile email.

#### Scenario: Device contains other calendars
- **WHEN** synchronization, full reset, profile replacement, disable, or cleanup runs on a device with unrelated calendars
- **THEN** no calendar, event, attendee, reminder, or extended property outside the application-owned calendar is inserted, updated, or deleted

#### Scenario: Another calendar has a matching display name
- **WHEN** an unrelated calendar has the same visible name as the application-owned calendar
- **THEN** ownership resolution excludes that unrelated calendar using the application-specific account identity

#### Scenario: Owned calendar is missing
- **WHEN** synchronization is enabled but the application-owned calendar no longer exists
- **THEN** the application recreates one read-only owned calendar before applying server events

#### Scenario: Owned calendar is presented by a calendar app
- **WHEN** a device calendar application displays the synchronized calendar
- **THEN** it can display events and reminders but is told by Calendar Provider that the calendar does not permit event modification

### Requirement: Idempotent page and checkpoint consistency
The application SHALL apply each received server page as an all-or-nothing set of owned-calendar operations and SHALL persist the page's returned synchronization key only after those operations succeed. Reprocessing the same page SHALL converge to the same calendar contents without duplicate events, attendees, reminders, or exceptions.

#### Scenario: Calendar page is committed
- **WHEN** every operation for a server response page succeeds
- **THEN** the application retains the page's returned synchronization key as the next incremental checkpoint

#### Scenario: Calendar page write fails
- **WHEN** any operation in a response page fails
- **THEN** the page is not partially visible, its returned synchronization key is not committed, and a later attempt can request and apply that page again

#### Scenario: Process stops after page write and before key persistence
- **WHEN** a page's idempotent calendar operations have taken effect but the process stops before its synchronization key is durably stored
- **THEN** replaying the page after restart updates the same server identities without creating duplicates and then stores the checkpoint

#### Scenario: Server invalidates a synchronization key
- **WHEN** the server rejects the retained folder or collection synchronization key as invalid
- **THEN** the application performs at most one automatic full reset for that run and treats a repeated invalidation as a user-actionable protocol problem
