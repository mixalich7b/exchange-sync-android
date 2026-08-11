## MODIFIED Requirements

### Requirement: Calendar event fidelity
The application SHALL map each supported Exchange calendar item to Android Calendar Provider data using the server identifier as a stable synchronization identity. The representation SHALL preserve the available UID, subject, body, location, start and end, all-day flag, time zone, recurrence, deleted and changed exceptions, organizer, `MeetingStatus`, `ResponseType`, `ResponseRequested`, current-user attendee response, availability, sensitivity, event status, and server reminder without exposing unparsed server payloads. It SHALL preserve at most 100 effective non-organizer attendees for each event or recurrence exception. The separately supplied organizer SHALL NOT count toward that limit. When an effective attendee list contains more than 100 entries, the application SHALL retain the supplied organizer representation, SHALL omit every non-organizer attendee row for that event or exception, and SHALL NOT reject the server page solely because the attendee list exceeded the local representation limit. Meeting-response classification SHALL use the complete server data before local attendee suppression. Meetings returned by the primary Calendar collection with `ResponseType` None, Not Responded, or Tentative SHALL remain in the owned Android calendar and SHALL be visually distinguished from accepted or organizer-owned meetings.

#### Scenario: Ordinary timed event is synchronized
- **WHEN** the server returns a non-recurring timed event with descriptive, organizer, attendee, availability, sensitivity, and status properties within the attendee limit
- **THEN** the owned Android event exposes the corresponding supported Calendar Provider values with the correct start, end, time zone, organizer, and attendees

#### Scenario: Event has exactly one hundred attendees
- **WHEN** an event or recurrence exception has exactly 100 effective non-organizer attendees
- **THEN** the owned Android representation preserves all 100 attendees and the separately supplied organizer

#### Scenario: Event exceeds the attendee limit
- **WHEN** an event or recurrence exception has 101 or more effective non-organizer attendees
- **THEN** the owned Android representation preserves the event and supplied organizer but contains no non-organizer attendee rows for that event or exception

#### Scenario: Oversized attendee list is received without an organizer
- **WHEN** an event or recurrence exception has more than 100 effective attendees and supplies no organizer
- **THEN** the event remains synchronized with no attendee rows and the application does not manufacture an organizer

#### Scenario: Existing event grows beyond the attendee limit
- **WHEN** a server change replaces an existing event's attendee list with more than 100 entries
- **THEN** the application removes every previously mirrored non-organizer attendee, retains the supplied organizer representation, and updates the same event identity

#### Scenario: Existing event falls back within the attendee limit
- **WHEN** a later server change replaces an organizer-only oversized representation with 100 or fewer attendees
- **THEN** the application restores the complete supplied attendee list on the same event identity without duplicates

#### Scenario: Pending meeting invitation is synchronized
- **WHEN** the primary Calendar `Sync` response returns an active received meeting with `ResponseType` None or Not Responded
- **THEN** the application creates or updates the same owned-calendar event with tentative event status, tentative availability, current-user attendee status Invited, and a paler event-specific color even when its attendee rows are suppressed

#### Scenario: Tentatively accepted meeting is synchronized
- **WHEN** the primary Calendar `Sync` response returns an active received meeting with `ResponseType` Tentative
- **THEN** the application creates or updates the same owned-calendar event with tentative event status, tentative availability, current-user attendee status Tentative, and the paler event-specific color even when its attendee rows are suppressed

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
- **WHEN** a received active meeting omits `ResponseType` but contains an unambiguous current-user attendee response in a list that may exceed the local attendee limit
- **THEN** the application derives the pending, tentative, accepted, or declined presentation from the complete server attendee response before deciding which attendee rows to retain

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

#### Scenario: Recurrence exception inherits an oversized attendee list
- **WHEN** a recurrence exception inherits more than 100 effective attendees from its series
- **THEN** the exception retains its organizer and response presentation but contains no non-organizer attendee rows

#### Scenario: Event has a reminder
- **WHEN** the server returns a supported reminder offset for an event
- **THEN** the application creates the corresponding Android alert reminder for that event

#### Scenario: Event has no reminder
- **WHEN** the server omits or disables the reminder for an event
- **THEN** the synchronized Android event has no reminder left from an earlier version

#### Scenario: Required event data is malformed
- **WHEN** a server item cannot be deterministically decoded or mapped without corrupting its identity, time, or recurrence
- **THEN** the application does not commit that response page or its next synchronization key and reports a user-actionable protocol-data problem

### Requirement: Idempotent page and checkpoint consistency
The application SHALL apply each received server page as an ordered sequence of owned-calendar provider sub-batches containing no more than 50 operations each and SHALL persist the page's returned synchronization key only after every sub-batch succeeds. References from attendee, reminder, organizer, or exception operations to rows inserted by an earlier sub-batch SHALL resolve to those exact owned-calendar rows. A failed, cancelled, obsolete, or interrupted page MAY leave successful earlier sub-batches locally visible, but reprocessing the unchanged checkpoint SHALL converge to the server representation without duplicate events, organizers, attendees, reminders, or exceptions.

#### Scenario: Page fits one provider sub-batch
- **WHEN** a planned page contains 50 or fewer Calendar Provider operations
- **THEN** the application applies it in one provider transaction and commits the returned synchronization key only after that transaction succeeds

#### Scenario: Page requires multiple provider sub-batches
- **WHEN** a planned page contains more than 50 Calendar Provider operations
- **THEN** the application preserves dependency order, sends every provider transaction with at most 50 operations, and commits the page synchronization key only after the final sub-batch succeeds

#### Scenario: Inserted parent is referenced by a later sub-batch
- **WHEN** an inserted event or recurrence exception and one of its child operations fall into different provider sub-batches
- **THEN** the child operation targets the provider identifier returned for that exact inserted parent and remains scoped to the application-owned calendar

#### Scenario: Calendar page is committed
- **WHEN** every provider sub-batch for a server response page succeeds
- **THEN** the application retains the page's returned synchronization key as the next incremental checkpoint

#### Scenario: Calendar page write fails
- **WHEN** one or more sub-batches have succeeded and a later sub-batch fails
- **THEN** the returned synchronization key is not committed, no later sub-batch is attempted, and the next eligible run requests the page again from the last committed checkpoint

#### Scenario: Process stops between provider sub-batches
- **WHEN** the process stops after at least one provider sub-batch has taken effect but before the page synchronization key is durably stored
- **THEN** replaying from the unchanged checkpoint removes or overwrites the partial child state, completes the same server identities without duplicates, and then stores the checkpoint

#### Scenario: Process stops after page write and before key persistence
- **WHEN** every provider sub-batch for a page has taken effect but the process stops before its synchronization key is durably stored
- **THEN** replaying the page after restart updates the same server identities and replaces their child collections without creating duplicates and then stores the checkpoint

#### Scenario: Replayed attendee replacement is idempotent
- **WHEN** replay begins after an earlier attempt removed or inserted only part of an event's attendees
- **THEN** the replay replaces the affected non-organizer attendee collection according to the current attendee-limit policy and produces no duplicate organizer or attendee rows

#### Scenario: Replayed recurrence replacement is idempotent
- **WHEN** replay begins after an earlier attempt removed or inserted only part of a recurring series' exception rows or their child data
- **THEN** the replay replaces the affected exceptions and child collections and converges without duplicate exception identities, attendees, or reminders

#### Scenario: Server invalidates a synchronization key
- **WHEN** the server rejects the retained folder or collection synchronization key as invalid
- **THEN** the application performs at most one automatic full reset for that run and treats a repeated invalidation as a user-actionable protocol problem

### Requirement: Adaptive calendar page sizing
The application SHALL preserve bounded HTTP, WBXML decoder, and Calendar Provider transaction limits while adapting the ActiveSync Calendar `WindowSize` to pages that exceed a page-scaled capacity limit. When a `Sync` response exceeds the bounded HTTP body or WBXML document or element capacity before local application, the application SHALL preserve existing calendar contents and the committed collection synchronization key. When a bounded Calendar Provider sub-batch exceeds provider transaction capacity, successful earlier sub-batches MAY remain locally visible, but the application SHALL NOT advance the collection synchronization key. At a window greater than one it SHALL halve the retained window and retry from the last committed checkpoint; at window one it SHALL report the existing terminal problem without skipping the server item. Structurally malformed WBXML, unsupported protocol structure, invalid calendar data, excessive nesting, and an oversized individual inline value SHALL remain distinct from a page-scaled capacity limit and SHALL NOT become recoverable merely by reducing the page window.

#### Scenario: WBXML element capacity is exceeded
- **WHEN** a valid Calendar `Sync` response exceeds the bounded WBXML element capacity at a window greater than one
- **THEN** the application classifies the response as a page-size failure, preserves the committed collection key and calendar contents, halves the window, and queues the same page for another attempt without entering blocked state

#### Scenario: Smaller window decodes successfully
- **WHEN** a page-size retry returns the same logical changes within the bounded decoder and provider capacities
- **THEN** the application applies the page in bounded provider sub-batches, commits its returned collection key with the reduced window, and continues the unfiltered synchronization while `MoreAvailable` is present

#### Scenario: Bounded provider sub-batch exceeds capacity
- **WHEN** a Calendar Provider sub-batch of no more than 50 operations exceeds provider transaction capacity at a window greater than one
- **THEN** the application preserves the last committed key, stops later sub-batches, halves the window, and retries the page so idempotent replay repairs any successful earlier sub-batches

#### Scenario: Single-item remote page remains over capacity
- **WHEN** a Calendar `Sync` response still exceeds a page-scaled HTTP or WBXML capacity at window one
- **THEN** the application preserves the last committed checkpoint and calendar contents and reports a user-actionable protocol-data problem without skipping the server item

#### Scenario: Single-item provider batch remains over capacity
- **WHEN** a bounded Calendar Provider sub-batch still exceeds provider transaction capacity at window one
- **THEN** the application preserves the last committed checkpoint, attempts no later sub-batch, and reports a user-actionable Calendar Provider problem without skipping the server item; a later replay remains able to repair any partial local state

#### Scenario: WBXML is structurally malformed
- **WHEN** a Calendar response violates WBXML syntax, encoding, nesting, protocol structure, or required calendar-data rules rather than a page-scaled capacity limit
- **THEN** the application does not reduce the window as a recovery attempt, advances no checkpoint, and reports the applicable protocol-data problem
