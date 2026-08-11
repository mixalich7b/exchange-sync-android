# calendar-sync Specification

## Purpose

Defines the one-way Exchange ActiveSync mirror that discovers the configured mailbox's primary calendar and represents its complete server-returned contents in one isolated, read-only Android calendar.
The implemented server-backed flow has been manually verified on a Xiaomi 17 running Android 16 against a real Exchange Server, including HTTPS/mTLS and ActiveSync, the real Calendar Provider, and actual execution of the 15-minute WorkManager periodic work; automated verification remains unit-only.

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
The application SHALL begin a full collection synchronization by priming the collection with an ActiveSync `SyncKey` of `0` without `GetChanges`, then use the synchronization key returned by that successful response to request changes without a past or future date filter. It SHALL continue requesting pages until the server reports no more available changes and retain all history and all future events returned by the server rather than imposing an application time horizon. It SHALL report successful completion only after every command returned by those pages has been decoded, applied to the owned calendar, and covered by a committed checkpoint; an empty ActiveSync `Sync` response SHALL mean that the server reported no pending changes and SHALL NOT be represented as imported events.

#### Scenario: Full synchronization primes the collection key
- **WHEN** a full synchronization has selected the primary Calendar collection
- **THEN** the application first sends `SyncKey=0` without `GetChanges`, persists no calendar page from that priming response, and sends unfiltered `GetChanges` only with the nonzero key returned by the server

#### Scenario: Full synchronization spans multiple pages
- **WHEN** the server returns one or more pages marked as having more changes available
- **THEN** the application applies every page in order and continues with the returned synchronization key until the complete collection has been received

#### Scenario: Server returns historical and future events
- **WHEN** an unfiltered full synchronization returns events before and after the current time
- **THEN** the owned Android calendar contains both the historical and future events before the full synchronization is reported complete

#### Scenario: Server limits retained history
- **WHEN** the server returns only a subset of historical events despite an unfiltered request
- **THEN** the application mirrors the complete subset returned by the server and does not manufacture or fetch events through a different protocol

#### Scenario: Server returns an empty full-sync page
- **WHEN** the unfiltered `GetChanges` request receives a valid empty `Sync` response with no pending server changes
- **THEN** the application commits no invented event, records that the server response was empty, and leaves a later incremental or manual run eligible to receive subsequent changes

### Requirement: ActiveSync recurrence exception identity compatibility
The application SHALL interpret every ActiveSync 16.0 and 16.1 `airsyncbase:InstanceId` as the protocol-defined UTC Compact DateTime of the original recurrence occurrence. It SHALL use that instant as the stable exception identity without requiring separators or fractional seconds that the protocol does not define.

#### Scenario: Compact InstanceId is returned
- **WHEN** an ActiveSync 16.0 or 16.1 calendar item contains an exception whose `InstanceId` is formatted as `yyyyMMdd'T'HHmmss'Z'`
- **THEN** the application decodes the occurrence identity and applies the recurring series and its exception instead of blocking the response page

#### Scenario: InstanceId is malformed
- **WHEN** an ActiveSync 16.0 or 16.1 exception contains an `InstanceId` that is not a valid UTC Compact DateTime
- **THEN** the application rejects the page without advancing its synchronization key and reports the existing protocol-data problem

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

### Requirement: Owned calendar cleanup compatibility
The application SHALL delete every application-owned calendar row through an Android Calendar Provider operation that remains scoped by the stable account identity, internal calendar name, and resolved provider identifier. It SHALL treat provider runtime rejection as an actionable cleanup failure and SHALL NOT broaden or redirect deletion to any unrelated local calendar.

#### Scenario: Owned calendar is deleted
- **WHEN** profile replacement, full reset, disable, or resumed cleanup requests deletion and the application-owned calendar exists
- **THEN** that calendar and its dependent events and reminders are removed while every unrelated calendar remains unchanged

#### Scenario: OEM local calendar also exists
- **WHEN** the device contains an unrelated calendar whose account identity is `account_name_local` or any value other than the application-owned identity
- **THEN** the application neither counts it as a duplicate owned calendar nor deletes or mutates it

#### Scenario: Calendar Provider rejects cleanup
- **WHEN** the provider throws a runtime, access, or security failure while the owned calendar is being queried or deleted
- **THEN** the cleanup remains incomplete with a durable actionable provider or permission problem and no unrelated calendar is affected

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

### Requirement: ActiveSync calendar cookie continuity
Calendar synchronization SHALL use the exact saved profile's process-local HTTP cookie session across capability discovery, HTTPS redirects, `FolderSync`, priming `Sync`, paged `Sync`, retries, and continuation slices. When no live session exists after process recreation, synchronization SHALL establish a fresh capability session before issuing calendar commands even when persisted protocol checkpoints are otherwise reusable, so newly issued eligible cookies can accompany those commands.

#### Scenario: OPTIONS cookie is used by FolderSync
- **WHEN** capability discovery for the saved profile receives a valid cookie that is eligible for the following `FolderSync` request
- **THEN** `FolderSync` sends that cookie in the request

#### Scenario: Command cookie is used by later pages
- **WHEN** `FolderSync`, priming `Sync`, or a paged `Sync` response updates an eligible cookie
- **THEN** every later eligible ActiveSync command in the same process-local profile session uses the updated cookie state

#### Scenario: Redirected command updates the session
- **WHEN** a permitted HTTPS ActiveSync redirect response sets a cookie scoped to the redirect destination
- **THEN** eligible requests to that destination use the cookie and requests to unrelated destinations do not receive it

#### Scenario: Cold process resumes persisted checkpoints
- **WHEN** synchronization resumes persisted ActiveSync checkpoints after process recreation and no process-local cookie session is available
- **THEN** it performs fresh capability discovery before the first calendar command, retains any resulting cookies in the new session, and continues with the mutually supported protocol version

#### Scenario: Saved profile changes
- **WHEN** a different connection profile becomes active
- **THEN** calendar synchronization does not send cookies retained for the previous profile identity

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

### Requirement: Run-scoped primary calendar preparation
The application SHALL obtain a current primary-calendar folder state before the first Calendar `Sync` command of each logical synchronization run and SHALL reuse that state across paged `Sync` requests, adaptive page-size retries, and continuation slices of the same run. It SHALL NOT repeat `FolderSync` merely because the server reports `MoreAvailable` or execution continues in another worker slice. Run-scoped folder state SHALL remain process-local and profile-bound and SHALL be discarded for a new logical run, process recreation, profile replacement, or a protocol outcome that invalidates the retained folder or collection state.

#### Scenario: Full synchronization spans multiple pages
- **WHEN** one logical full synchronization receives multiple pages with `MoreAvailable`
- **THEN** it performs one successful folder preparation for the run and reuses the resulting opaque primary collection identifier for the remaining pages and continuation slices

#### Scenario: Oversized page is retried
- **WHEN** an adaptive page-size retry continues the same logical run from an unchanged collection checkpoint
- **THEN** the retry reuses the run's prepared folder state and does not issue another `FolderSync` solely because the window changed

#### Scenario: Later logical run starts
- **WHEN** a new manual, periodic, profile-triggered, or recovery run starts after the previous logical run ends
- **THEN** the application refreshes the folder hierarchy from the retained folder synchronization key before issuing that run's first Calendar `Sync`

#### Scenario: Process is recreated during synchronization
- **WHEN** a continuation resumes persisted checkpoints without the prior process-local prepared folder state
- **THEN** the application performs fresh capability and folder preparation before Calendar `Sync` and then reuses the newly prepared state for the remainder of that logical run

#### Scenario: Folder or collection state is invalidated
- **WHEN** ActiveSync reports that retained folder or collection state is no longer valid
- **THEN** the application discards the run-scoped folder state and follows the existing fenced reset or actionable failure policy instead of continuing with a stale primary collection identifier

### Requirement: Paced sequential synchronization requests
During calendar synchronization, the application SHALL dispatch each top-level ActiveSync request after the first no earlier than two seconds after the preceding top-level request reaches a terminal response or failure. The pacing SHALL apply across capability discovery, `FolderSync`, priming `Sync`, paged `Sync`, adaptive retries, and continuation slices that share the live process-local profile session. HTTPS redirect hops SHALL remain part of their originating top-level exchange rather than incurring separate pacing delays. A longer existing retry, scheduling, network, or processing interval SHALL satisfy the minimum and SHALL NOT receive an additional mandatory two-second delay. Pacing waits SHALL be cooperatively cancellable and SHALL send no request after the synchronization fence becomes obsolete or cancellation is observed.

#### Scenario: FolderSync follows capability discovery
- **WHEN** successful capability discovery is immediately followed by `FolderSync` in a calendar synchronization run
- **THEN** `FolderSync` is dispatched no earlier than two seconds after the capability exchange completes

#### Scenario: Paged Sync has more changes
- **WHEN** a Calendar `Sync` page completes with `MoreAvailable` and the next request would otherwise be immediate
- **THEN** the next top-level `Sync` request is dispatched no earlier than two seconds after the prior exchange completes

#### Scenario: Existing interval is already longer
- **WHEN** local page application, worker continuation, or retry backoff leaves at least two seconds between the previous exchange completion and the next request
- **THEN** the next request proceeds without adding another two-second delay

#### Scenario: Request follows an HTTPS redirect
- **WHEN** a top-level ActiveSync exchange follows one or more permitted HTTPS redirects before reaching its terminal response
- **THEN** redirect hops proceed under the existing redirect and timeout policy, and the two-second interval begins only after the terminal exchange completes

#### Scenario: Synchronization is cancelled during pacing
- **WHEN** cancellation or fence invalidation occurs while a request is waiting for its pacing interval
- **THEN** the wait terminates cooperatively and the pending network request is not sent
