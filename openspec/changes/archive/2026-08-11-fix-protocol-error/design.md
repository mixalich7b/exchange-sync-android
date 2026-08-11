## Context

See [proposal.md](proposal.md) for the observed failure. The current WBXML reader throws one `WbxmlFormatException` for syntax errors and every safety-limit violation. The ActiveSync codec then converts every such exception to `MALFORMED_WBXML`, so a Calendar page that only exceeds the 20,000-element limit becomes a critical protocol-data failure.

The recovery path needed after a page-scaled limit already exists: a bounded HTTP response maps to `WINDOW_TOO_LARGE`, and `ExecuteSynchronizationSlice` preserves the checkpoint, halves the persisted window, and queues a continuation; Calendar Provider transaction overflow follows the same state-machine pattern. The missing boundary is the typed classification between WBXML decoding and that existing remote-page outcome.

Separately, `ActiveSyncRemoteCalendar.fetchPage` prepares capabilities from process-local profile session state but calls `FolderSync` for every page. The synchronization state machine serializes a logical run and keeps its generation and run token across continuation slices, so those fence values can safely scope process-local prepared folder state.

The response body, event content, account identity, folder names, collection identifiers, and synchronization keys remain sensitive. They may participate in protocol state but must not enter diagnostics.

## Goals / Non-Goals

**Goals:**

- Reuse the existing adaptive window state machine for Calendar response limits that scale with the number of commands on a page.
- Preserve strict decoder limits and distinguish capacity exhaustion from malformed or unsupported data.
- Preserve atomic page and checkpoint ordering during every retry.
- Perform folder hierarchy preparation once per live logical run in the normal case while retaining deterministic refresh and invalidation boundaries.
- Enforce a shared, cancellable two-second minimum interval between top-level synchronization exchanges without changing redirect or retry semantics.
- Make the recovery decision diagnosable with bounded, allow-listed fields.

**Non-Goals:**

- Estimating event complexity before a server response, dynamically raising decoder limits, or splitting one server page into independently committed local fragments.
- Skipping a server item that cannot fit at window one.
- Persisting a new folder cache or changing the persisted checkpoint schema.
- Changing periodic work cadence, page-slice limits, unfiltered history retrieval, or WorkManager cancellation behavior.

## Decisions

### 1. Model WBXML read limits separately from format errors

The WBXML reader will expose a typed read-limit failure with an allow-listed limit kind. Document-byte and element-count limits are page-scaled for a Calendar `Sync` response; depth and individual inline-string limits are not assumed to improve when `WindowSize` decreases. WBXML syntax, token, encoding, string-table, duplicate-singleton, and protocol-structure violations remain format or protocol-data errors.

The Calendar decode boundary will preserve a page-scaled read-limit failure long enough for `ActiveSyncRemoteCalendar` to emit a safe capacity diagnostic and return the existing `SyncFailureKind.WINDOW_TOO_LARGE`. A corresponding limit while decoding `FolderSync`, a request produced by the writer, excessive nesting, or one oversized inline value will remain a non-retryable protocol/compatibility outcome because reducing the Calendar window cannot address it.

This approach keeps the existing numeric safety bounds and recovery state machine. Raising `maxElements` was rejected because it only postpones the failure and increases memory/stack pressure. Matching exception message text was rejected because messages are not a stable policy boundary and would reproduce the current classification bug.

### 2. Reuse the existing adaptive window and checkpoint transaction

No new core failure category is required. `WINDOW_TOO_LARGE` already means that the current Calendar page must be retried with a smaller server window. The existing reducer will continue to:

1. leave the current collection key and calendar unchanged;
2. halve `windowSize` with a lower bound of one;
3. persist the reduced window and queue a continuation for the same fence;
4. block with `PROTOCOL_DATA` if a remote page still exceeds capacity at window one.

Provider transaction overflow retains its current equivalent flow and its `CALENDAR_PROVIDER` terminal category at window one. A successful smaller response is applied atomically before its returned key and reduced window are committed.

Increasing a limit or retrying the same window was rejected because neither changes page complexity. Advancing the key or dropping one command was rejected because either action can permanently omit an event from the mirror.

### 3. Cache prepared folder state by profile and synchronization fence

`ActiveSyncProfileSession` will retain at most one prepared folder state alongside its capability and cookie session. The state will contain the synchronization fence, selected protocol version, terminal command endpoint, returned folder key, and opaque primary collection identifier. It remains in memory and is already bounded by the profile-session registry.

Folder preparation will use the cached value only when the profile session, generation, run token, and protocol version match. A cache miss performs `FolderSync` with the persisted folder checkpoint, validates the primary calendar exactly as today, and records the successful prepared state before the first Calendar `Sync`. Later pages, window reductions, and continuation workers with the same fence reuse it. The next logical run has a different run token and therefore refreshes the hierarchy; process recreation also starts with an empty cache.

An invalid folder or collection key, full-reset outcome, profile replacement, or failed primary-calendar reconciliation clears the prepared state before existing recovery policy runs. Folder changes returned during preparation remain durable only through the existing successful calendar-page checkpoint commit; process death before that commit safely repeats `FolderSync` from the prior persisted key.

Persisting the cache was rejected because the existing checkpoints already contain the durable folder state and a cold process must re-establish capability/cookie context. Reusing folder state across unrelated run tokens was rejected because it could suppress hierarchy refresh indefinitely.

### 4. Add bounded diagnostics at the classification and cache boundaries

The decoder/remote boundary will emit stable reason codes for document or element capacity and retain distinct malformed, depth, and inline-value reasons. The existing synchronization diagnostic will record the subsequent window reduction or minimum-window block. Together, records correlated by generation and run token show the old window, safe capacity kind, reduced window, unchanged-checkpoint continuation, or terminal category.

Folder preparation will emit an allow-listed outcome such as refresh, reuse, cold refresh, or invalidation plus the existing command outcome. It will not log cache contents. Diagnostic-model and formatter tests will assert that opaque collection/folder values, keys, payloads, and profile identity cannot be rendered.

Logging the exception message alone was rejected because it currently presents a client capacity limit as malformed server data and may expose unstable implementation text.

### 5. Pace top-level synchronization exchanges in the shared profile session

The process-local ActiveSync profile session will own a monotonic request pacer shared by capability and command gateways. Before dispatching a top-level synchronization exchange, the pacer serializes access and waits only for the remainder of a two-second minimum interval measured from completion of the previous top-level exchange. It records completion in `finally` so transport failures are paced as well as successful responses. The first request in a fresh session is immediate.

The pacing boundary surrounds the whole redirect chain, not each HTTP hop. This preserves existing redirect timeouts and avoids adding repeated sleeps during endpoint negotiation. Existing local mapping time, WorkManager continuation latency, and exponential backoff count toward the interval; if at least two seconds have already elapsed, the next request proceeds immediately. Coroutine delay and monotonic time make the wait cancellation-safe and unit-testable without wall-clock sleeps. The caller revalidates the synchronization fence before the actual network dispatch, so a cancelled or obsolete run cannot send after waiting.

Putting an unconditional `delay(2000)` in the pagination loop was rejected because it would miss capability-to-folder and folder-to-priming transitions, add unnecessary delay after longer processing/backoff, and couple transport policy to one orchestration path. Pacing individual redirect hops was rejected because redirects form one logical exchange and must remain within the existing bounded redirect/timeout policy. Persisting the timestamp was rejected because a process restart is already a natural cold-session boundary and wall-clock state would add no meaningful server protection.

## Risks / Trade-offs

- [A server ignores `WindowSize` or one event alone exceeds the page-scaled limit] → Continue halving only to one, then block without advancing the checkpoint or hiding the item.
- [Folder hierarchy changes during a long paginated run] → Keep the run internally consistent, refresh at the next logical run, and discard cached state immediately if the server invalidates the folder or collection key.
- [A broader typed exception accidentally makes malformed data retryable] → Limit adaptive mapping to explicitly page-scaled read-limit kinds at the Calendar response boundary and add negative regressions for syntax, depth, inline-string, FolderSync, and request encoding failures.
- [A cancelled worker causes duplicate preparation] → Record prepared state immediately after successful validation; if cancellation or process death occurs before that point, repeating idempotent `FolderSync` is safe.
- [Additional cache state retains opaque server identifiers longer in memory] → Keep it process-local, profile-bound, single-entry per bounded profile session, clear it on invalidation, and exclude it from logs.
- [Pacing lengthens a large historical import] → Apply only the requested two-second minimum inside active synchronization; continuation slices and checkpoints keep the work resumable, and redundant `FolderSync` removal offsets part of the added latency.
- [Concurrent callers race around the interval] → Serialize the profile-session pacer and re-check cancellation/fence validity immediately before transport dispatch.

## Migration Plan

No persisted-state or database migration is required. Existing checkpoints, including a reduced window and a currently blocked last committed collection key, remain compatible. After upgrade, a user can invoke the existing retry action; the run refreshes its folder state, retries the blocked page, and automatically reduces the window if the typed capacity condition recurs. A full reset is neither required nor desirable because it would re-import old history.

Rollback restores the prior classification and request pattern without transforming stored data. Previously committed calendar pages and checkpoints remain readable, although the same high-complexity page can block again under the old behavior.
