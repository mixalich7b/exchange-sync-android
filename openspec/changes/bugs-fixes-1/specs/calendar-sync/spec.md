## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: ActiveSync recurrence exception identity compatibility
The application SHALL interpret every ActiveSync 16.0 and 16.1 `airsyncbase:InstanceId` as the protocol-defined UTC Compact DateTime of the original recurrence occurrence. It SHALL use that instant as the stable exception identity without requiring separators or fractional seconds that the protocol does not define.

#### Scenario: Compact InstanceId is returned
- **WHEN** an ActiveSync 16.0 or 16.1 calendar item contains an exception whose `InstanceId` is formatted as `yyyyMMdd'T'HHmmss'Z'`
- **THEN** the application decodes the occurrence identity and applies the recurring series and its exception instead of blocking the response page

#### Scenario: InstanceId is malformed
- **WHEN** an ActiveSync 16.0 or 16.1 exception contains an `InstanceId` that is not a valid UTC Compact DateTime
- **THEN** the application rejects the page without advancing its synchronization key and reports the existing protocol-data problem

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
