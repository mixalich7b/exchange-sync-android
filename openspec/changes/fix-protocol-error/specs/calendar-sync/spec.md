## ADDED Requirements

### Requirement: Adaptive calendar page sizing
The application SHALL preserve bounded HTTP, WBXML decoder, and Calendar Provider transaction limits while adapting the ActiveSync Calendar `WindowSize` to pages that exceed a page-scaled capacity limit. When a `Sync` response exceeds the bounded HTTP body or WBXML document or element capacity, or when its atomic owned-calendar batch exceeds the provider transaction capacity, the application SHALL write no partial page, SHALL NOT advance the collection synchronization key, SHALL halve the retained window to a minimum of one, and SHALL retry from the last committed checkpoint. It SHALL keep structurally malformed WBXML, unsupported protocol structure, invalid calendar data, excessive nesting, and an oversized individual inline value distinct from a page-scaled capacity limit and SHALL NOT make those failures recoverable merely by reducing the page window.

#### Scenario: WBXML element capacity is exceeded
- **WHEN** a valid Calendar `Sync` response exceeds the bounded WBXML element capacity at a window greater than one
- **THEN** the application classifies the response as a page-size failure, preserves the committed collection key and calendar contents, halves the window, and queues the same page for another attempt without entering blocked state

#### Scenario: Smaller window decodes successfully
- **WHEN** a page-size retry returns the same logical changes within the bounded decoder and provider capacities
- **THEN** the application applies the page atomically, commits its returned collection key with the reduced window, and continues the unfiltered synchronization while `MoreAvailable` is present

#### Scenario: Single-item remote page remains over capacity
- **WHEN** a Calendar `Sync` response still exceeds a page-scaled HTTP or WBXML capacity at window one
- **THEN** the application preserves the last committed checkpoint and calendar contents and reports a user-actionable protocol-data problem without skipping the server item

#### Scenario: Single-item provider batch remains over capacity
- **WHEN** the atomic Calendar Provider batch still exceeds provider transaction capacity at window one
- **THEN** the application preserves the last committed checkpoint and calendar contents and reports a user-actionable Calendar Provider problem without partially applying or skipping the server item

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
