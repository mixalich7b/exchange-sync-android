## ADDED Requirements

### Requirement: Adaptive page and folder-preparation diagnostics
The application SHALL emit privacy-safe correlated diagnostics that distinguish page-scaled capacity recovery from malformed WBXML and that identify whether primary-calendar folder preparation was refreshed or reused. Capacity records SHALL identify the bounded capacity kind, command, current window, recovery or terminal outcome, and reduced window when applicable. Folder-preparation records SHALL identify refresh, reuse, invalidation, and process-cold outcomes without logging folder names, collection identifiers, synchronization keys, profile identity, or protocol payloads.

#### Scenario: WBXML page capacity triggers recovery
- **WHEN** a Calendar `Sync` response exceeds the bounded WBXML document or element capacity at a window greater than one
- **THEN** correlated records identify a WBXML capacity outcome, the current and reduced bounded window values, unchanged-checkpoint recovery, and continuation without labeling the response as malformed protocol data

#### Scenario: WBXML syntax is malformed
- **WHEN** a response violates WBXML syntax, encoding, nesting, or protocol structure
- **THEN** correlated records identify the stable malformed-data validation reason and terminal protocol classification rather than a page-size recovery

#### Scenario: Capacity remains exceeded at window one
- **WHEN** the same remote or provider capacity remains exceeded at the minimum window
- **THEN** correlated records identify the capacity kind, minimum-window terminal outcome, and resulting safe problem category without logging the event or response content

#### Scenario: Prepared folder state is reused
- **WHEN** another page, adaptive retry, or continuation slice in the same logical run uses the process-local prepared folder state
- **THEN** correlated diagnostics record a bounded reuse outcome without a second successful `FolderSync` request record and without exposing the primary collection identifier or folder synchronization key

#### Scenario: Folder state is refreshed
- **WHEN** a new logical run, cold process, or invalidated folder state requires `FolderSync`
- **THEN** correlated diagnostics record the safe refresh reason and command outcome without exposing folder names, identifiers, or synchronization keys
