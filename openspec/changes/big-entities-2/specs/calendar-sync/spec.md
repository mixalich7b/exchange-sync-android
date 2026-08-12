## MODIFIED Requirements

### Requirement: Adaptive calendar page sizing
The application SHALL preserve bounded HTTP, WBXML decoder, and Calendar Provider transaction limits while adapting the ActiveSync Calendar `WindowSize` to pages that exceed a page-scaled capacity limit. The bounded WBXML element capacity for a Calendar `Sync` response SHALL admit up to 256,000 elements so that a document-bounded single item can contain complete large attendee lists and supported recurrence exceptions. When a `Sync` response exceeds the bounded HTTP body or WBXML document or element capacity before local application, the application SHALL preserve existing calendar contents and the committed collection synchronization key. When a bounded Calendar Provider sub-batch exceeds provider transaction capacity, successful earlier sub-batches MAY remain locally visible, but the application SHALL NOT advance the collection synchronization key. At a window greater than one it SHALL halve the retained window and retry from the last committed checkpoint; at window one it SHALL report the existing terminal problem without skipping the server item. Structurally malformed WBXML, unsupported protocol structure, invalid calendar data, excessive nesting, and an oversized individual inline value SHALL remain distinct from a page-scaled capacity limit and SHALL NOT become recoverable merely by reducing the page window.

#### Scenario: High-element recurring item decodes at the minimum window
- **WHEN** a `WindowSize=1` Calendar `Sync` response fits every bounded decoder limit, contains no more than 256,000 WBXML elements, and returns one valid recurring item with more than 200 attendees repeated across changed and deleted recurrence exceptions
- **THEN** the application decodes and applies the item, preserves its organizer and recurrence exceptions under the existing attendee-representation policy, commits the returned synchronization key after local application, and does not report a minimum-window capacity problem

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
