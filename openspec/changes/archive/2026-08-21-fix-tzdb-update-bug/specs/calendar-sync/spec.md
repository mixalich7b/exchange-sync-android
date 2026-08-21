## MODIFIED Requirements

### Requirement: Incremental one-way calendar mirror
After a complete full synchronization, the application SHALL use the last committed collection synchronization key to apply server additions, changes, deletions, and soft deletions. It SHALL send no client calendar `Add`, `Change`, `Delete`, meeting response, or other calendar mutation to Exchange. When a partial server change omits unchanged time properties for a clean recurring event, the application SHALL preserve the previously synchronized time range from the canonical recurring Calendar Provider representation. A present recurrence rule, present start, and parseable positive duration SHALL make the duration-derived end authoritative over an absent or epoch-zero provider end. This local normalization SHALL NOT override an explicit time value returned by Exchange or reinterpret a non-recurring event.

#### Scenario: Server adds an event
- **WHEN** an incremental `Sync` response adds a previously unknown server event
- **THEN** the application creates the corresponding event and child data in its owned Android calendar

#### Scenario: Server changes an event
- **WHEN** an incremental `Sync` response changes a known server event
- **THEN** the application updates that event and its affected child data without creating a duplicate

#### Scenario: Partial server change omits unchanged properties
- **WHEN** the selected ActiveSync version defines omitted properties in a change as unchanged
- **THEN** the application preserves the previously synchronized values of those properties

#### Scenario: Tzdb maintenance leaves an epoch-zero recurring end
- **WHEN** a clean owned recurring event has a present start, recurrence rule, and parseable positive duration, Calendar Provider exposes its end as epoch zero after timezone-database maintenance, and a partial server `Change` omits start and end
- **THEN** the application derives the unchanged end from the local start and duration, applies the change to the same server identity, and leaves the returned synchronization key eligible for normal commit without requiring a full reset

#### Scenario: Recurring provider snapshot has no trustworthy duration
- **WHEN** a clean owned recurring event exposes an epoch-zero end but its duration is absent, malformed, zero, or negative and a partial server `Change` supplies no replacement time range
- **THEN** the application does not manufacture an end, does not commit the response page or its synchronization key as successfully merged, and follows the existing actionable failure policy

#### Scenario: Server explicitly supplies an invalid time range
- **WHEN** an ActiveSync `Add` or `Change` explicitly supplies an end that is equal to or earlier than its effective start
- **THEN** the application rejects the server page without substituting a locally derived recurring end or advancing the synchronization key

#### Scenario: Non-recurring event has an epoch-zero end
- **WHEN** a non-recurring provider snapshot exposes epoch zero as its end and a partial server `Change` supplies no replacement time range
- **THEN** the application does not apply recurring-duration normalization and follows the existing invalid-time or reset policy

#### Scenario: Server deletes an event
- **WHEN** an incremental response deletes or soft-deletes a known server event
- **THEN** the application removes that event and its associated reminders, attendees, and recurrence exceptions from its owned Android calendar

#### Scenario: Local event mutation is observed
- **WHEN** another local component modifies a synchronized event despite the calendar's read-only declaration
- **THEN** the application never uploads the mutation and a later server change or full synchronization restores the server representation
