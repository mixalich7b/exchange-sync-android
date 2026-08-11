## Why

Device diagnostics from a real unfiltered full synchronization show a valid HTTP 200 `Sync` response being classified as malformed protocol data only because its page exceeds the client's WBXML element-count safety limit. That critical classification blocks the run before newer events are reached, while rediscovering the folder hierarchy before every page also creates unnecessary ActiveSync traffic during long history imports.

## What Changes

- Distinguish a structurally malformed WBXML response from a response that exceeds a page-scaled decoder resource limit.
- Route a page-scaled WBXML limit through the existing adaptive window recovery: preserve the committed checkpoint, halve `WindowSize`, and retry the same page until it succeeds or window one still cannot be decoded.
- Keep genuine WBXML syntax, structure, depth, encoding, and value failures non-retryable, and keep the decoder's safety limits in place.
- Reuse a successfully prepared primary-calendar folder state across pages and continuation slices of the same logical run, while refreshing it for a new logical run, a cold process, or invalidated folder state.
- Pace sequential top-level ActiveSync requests in calendar synchronization so the next request is not dispatched until at least two seconds after the previous exchange completes, without delaying redirect hops or stacking an extra delay on a longer retry backoff.
- Emit privacy-safe diagnostics that identify decoder-limit recovery separately from malformed protocol data and make folder-state reuse versus refresh visible without exposing collection identifiers or synchronization keys.
- Add unit regressions for classification, checkpoint preservation, adaptive recovery, minimum-window blocking, and bounded `FolderSync` frequency; update the affected developer documentation.

Non-goals:

- Changing the unfiltered all-history synchronization contract, server-defined result ordering, or the periodic synchronization interval; the two-second request pacing applies only inside an active calendar synchronization run.
- Raising or removing WBXML, HTTP-body, or Calendar Provider safety limits.
- Skipping a single oversized or malformed event and advancing the server checkpoint, which would leave a permanently incomplete mirror.
- Changing the already working adaptive recovery for oversized Calendar Provider transactions.
- Speculatively changing WorkManager cancellation policy without a reproducible scheduling defect.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `calendar-sync`: recover from page-scaled WBXML decoder limits with the existing adaptive synchronization window, avoid redundant folder discovery within one logical run, and pace sequential synchronization requests by two seconds.
- `diagnostic-logging`: distinguish recoverable WBXML resource-limit events from malformed protocol responses and report safe folder-preparation outcomes.

## Impact

- `:infrastructure` ActiveSync WBXML exception modeling, response decoding, remote calendar orchestration, process-local profile session and request-pacing state, and diagnostics.
- `:core` synchronization failure handling only where needed to preserve the existing `WINDOW_TOO_LARGE` recovery contract; no Android dependency or module-direction change.
- Existing unit-test suites for WBXML codecs, ActiveSync remote calendar behavior, synchronization use cases, session continuity, and diagnostics.
- `docs/calendar-sync.md` and `docs/diagnostics.md` must describe the implemented recovery and request-frequency behavior before archival.
- No new production dependency, public service API, credential handling, calendar ownership, or network endpoint change.
