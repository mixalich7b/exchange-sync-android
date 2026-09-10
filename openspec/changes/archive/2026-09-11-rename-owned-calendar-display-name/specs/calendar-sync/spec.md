## MODIFIED Requirements

### Requirement: Isolated read-only Android calendar ownership
The application SHALL create at most one visible local Android calendar with a stable application-specific account identity, the display name `Exchange-sync`, and read-only access. Every query, insert, update, clear, and delete SHALL be scoped to that owned identity and, once resolved, its provider calendar identifier; no operation SHALL select another calendar merely by display name, owner email, or profile email. On the first launch after an upgrade containing this requirement, the application SHALL rename every existing application-owned calendar to `Exchange-sync` without deleting or recreating its events. The migration SHALL be retried after a Calendar Provider failure and SHALL NOT modify unrelated calendars.

#### Scenario: Device contains other calendars
- **WHEN** synchronization, full reset, profile replacement, disable, cleanup, or display-name migration runs on a device with unrelated calendars
- **THEN** no calendar, event, attendee, reminder, or extended property outside the application-owned calendar is inserted, updated, or deleted

#### Scenario: Another calendar has a matching display name
- **WHEN** an unrelated calendar has the same visible name as the application-owned calendar
- **THEN** ownership resolution excludes that unrelated calendar using the application-specific account identity

#### Scenario: Owned calendar is missing
- **WHEN** synchronization is enabled but the application-owned calendar no longer exists
- **THEN** the application recreates one read-only owned calendar with display name `Exchange-sync` before applying server events

#### Scenario: Owned calendar is presented by a calendar app
- **WHEN** a device calendar application displays the synchronized calendar
- **THEN** it can display events and reminders, shows `Exchange-sync`, and is told by Calendar Provider that the calendar does not permit event modification

#### Scenario: Existing owned calendar is migrated after upgrade
- **WHEN** the updated application starts and an existing calendar matches the complete stable application-owned identity
- **THEN** it changes only that calendar's display name to `Exchange-sync`, preserves its provider identifier and all dependent data, and records the migration as complete

#### Scenario: Display-name migration cannot access Calendar Provider
- **WHEN** the updated application cannot query or update the owned calendar during startup migration
- **THEN** it does not record the migration as complete, leaves unrelated calendars unchanged, and retries the migration on a later application start

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
