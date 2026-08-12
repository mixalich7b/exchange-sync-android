## ADDED Requirements

### Requirement: Recurrence exception response fallback
The application SHALL treat a recurrence exception's response as an optional override of the recurring series response. An explicit exception `ResponseType` SHALL remain authoritative. When the exception omits `ResponseType`, the application SHALL derive an override only from exactly one attendee matching the configured profile email whose `AttendeeStatus` is present and supported. If no such unambiguous attendee response exists, the exception SHALL retain no new response override and SHALL follow the existing merge rules: a previously synchronized explicit exception override is preserved for a partial change, otherwise the occurrence inherits the series response. This optional exception fallback SHALL NOT weaken the requirement to reject a received meeting series whose response cannot itself be classified.

#### Scenario: Exception current-user attendee omits status
- **WHEN** a recurring meeting has a classifiable series response and an exception omits `ResponseType` while its attendee list contains the configured profile email without `AttendeeStatus`
- **THEN** the application accepts the server item, applies the series response presentation to that occurrence, and leaves the response page eligible for normal atomic commit

#### Scenario: Exception attendee response supplies an override
- **WHEN** an exception omits `ResponseType` and contains exactly one attendee matching the configured profile email with a supported `AttendeeStatus`
- **THEN** the application uses that attendee status as the response override for that occurrence

#### Scenario: Explicit exception response remains authoritative
- **WHEN** an exception supplies a supported `ResponseType` and its current-user attendee status is omitted or differs
- **THEN** the application uses the explicit exception `ResponseType` for that occurrence

#### Scenario: Partial exception response omission preserves a prior override
- **WHEN** a partial change for a previously synchronized exception omits `ResponseType` and does not supply an unambiguous current-user attendee status
- **THEN** the application preserves the previously synchronized explicit exception response override

#### Scenario: Received series response remains unclassifiable
- **WHEN** an exception has no usable response override and the received meeting series contains neither `ResponseType` nor an unambiguous current-user attendee response
- **THEN** the application rejects the page without committing its next synchronization key and reports a user-actionable protocol-data problem
