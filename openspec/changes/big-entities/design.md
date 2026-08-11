## Context

See `proposal.md` for motivation. The current adapter converts a whole ActiveSync page into one flat `CalendarProviderBatchPlan` and sends every operation through one `ContentResolver.applyBatch` call. Inserts are linked to organizer, attendee, reminder, and exception operations by batch-index back references. This provides whole-page transaction semantics but cannot cross Android's Binder size boundary, and a single meeting with thousands of attendees remains terminal even at ActiveSync `WindowSize=1`.

The current mapper also needs the complete attendee list to derive a missing meeting `ResponseType`, while the provider planner expands every retained attendee into an individual operation. The change must therefore bound only the local representation after semantic mapping, not truncate protocol parsing. Existing `_SYNC_ID` upsert, complete child-collection replacement, fenced mutation lock, and checkpoint-after-provider ordering provide the recovery primitives to reuse.

## Goals / Non-Goals

**Goals:**

- Keep every supported event synchronized while bounding non-organizer attendee materialization at 100 entries per effective event or exception list.
- Send no more than 50 operations in one Calendar Provider call, including pages and individual entities larger than one sub-batch.
- Resolve insert dependencies correctly across sub-batch boundaries and check the active fence before every provider side effect.
- Make an interrupted or failed multi-batch page converge on replay while retaining the last committed ActiveSync key until the page completes.
- Preserve privacy-safe evidence of suppression, sub-batch progress, and confirmed versus ambiguous application outcomes.

**Non-Goals:**

- Estimating or negotiating the Binder byte limit, dynamically changing either configured limit, or adding a second storage engine.
- Preserving a representative subset of an oversized attendee list or storing omitted identities outside Calendar Provider.
- Providing atomic rollback across multiple public Calendar Provider calls.
- Adding instrumentation tests, new modules, production dependencies, or UI controls.

## Decisions

### 1. Suppress oversized attendees only at provider planning

Protocol decoding and `CalendarEventMapper` will continue to receive the complete attendee list so meeting-response fallback and validation remain unchanged. The provider planner will count the effective non-organizer attendees of the event and of each recurrence exception independently. The organizer is a separate protocol/property representation and is excluded from the threshold.

For a list of 100 or fewer entries, planning remains unchanged. For 101 or more, planning will still perform the existing non-organizer attendee replacement cleanup but will emit no non-organizer attendee inserts. Organizer replacement, event fields, response presentation, reminder, recurrence, and exceptions remain planned. If no organizer was supplied, none will be manufactured. The provider values that describe attendee presence must agree with the retained organizer-only or empty representation.

This placement also handles transitions deterministically: a change from a small to a large list removes previously stored attendees, while a later small list restores all supplied attendees. Applying the same rule to effective exception attendees prevents inherited series attendees from multiplying into thousands of exception child operations.

Truncating in the parser was rejected because it could discard the current user's response before classification. Keeping the first 100 attendees was rejected because an arbitrary partial roster is misleading and order-dependent. Rejecting or skipping the whole event was rejected because it would continue blocking the checkpoint or silently lose more useful event data than necessary.

### 2. Preserve the flat plan but page it with dependency-aware reference resolution

The existing planner's global operation order will remain the canonical page plan. A pure sub-batch cursor will expose the next consecutive group of at most 50 operations and maintain a mapping from global insert-operation indexes to provider row identifiers returned by successful earlier sub-batches.

When preparing a sub-batch, each backward `Inserted` reference will be rewritten as follows:

- a referenced insert in the same sub-batch becomes a sub-batch-local back reference;
- a referenced insert from an earlier successful sub-batch becomes an explicit existing provider row reference;
- a missing result or a forward reference is a planning/provider consistency failure and stops the page.

The gateway will apply exactly one sub-batch and return validated identifiers for its event and exception insert results. The cursor records those identifiers before producing the next sub-batch. This retains the existing operation order and supports parent events or exceptions whose child operations extend beyond one sub-batch.

Blindly calling `operations.chunked(50)` was rejected because existing back-reference indexes are global and would target the wrong operation or fall outside a later batch. Grouping only by event was rejected because one allowed event or exception can itself require more than 50 operations. Re-querying Calendar Provider after every sub-batch was rejected as unnecessary overhead when successful insert results already provide the required identifiers.

### 3. Orchestrate sub-batches at the adapter boundary

The adapter will retain the existing page-scoped mutation lock and query/plan once. It will check the synchronization fence immediately before every provider call, apply the next sub-batch through the gateway, record its results, and stop immediately on cancellation, obsolescence, or failure. An empty plan performs no provider call and succeeds with zero applied operations.

The one-call gateway contract will return structured insert results instead of `Unit`. Android conversion remains inside the gateway because only that layer owns `ContentProviderOperation`, `ContentProviderResult`, sync-adapter URIs, and provider exception mapping. Pure planner/cursor tests will exercise reference rewriting without Android IPC.

Keeping the whole paging loop hidden inside the gateway was rejected because it would prevent the suspend adapter from checking the current generation/run-token fence between side effects.

### 4. Use unchanged-checkpoint replay instead of a durable sub-batch journal

The collection SyncKey will be stored only after the final sub-batch succeeds, as today. No sub-batch offset or payload will be persisted. If execution stops after a successful sub-batch, the next eligible run requests the logical page again from the unchanged key and replans against current owned-calendar rows.

Replay convergence relies on existing stable identities and replacement ordering:

- a top-level event inserted before interruption is found by `_SYNC_ID` and becomes an update rather than a duplicate insert;
- attendee and reminder replacements delete the affected existing child collection before inserting its desired contents;
- exception replacement deletes existing series exceptions before recreating the complete server set;
- repeated event deletes are harmless when the row is already absent;
- the attendee-limit rule is deterministic for the same server data.

The same strategy covers an ambiguous failed Binder call: the adapter records only earlier confirmed operations, assumes the failed sub-batch may have taken effect, leaves the checkpoint unchanged, and relies on whole-page replay rather than retrying only that sub-batch from an in-memory offset.

A durable operation journal was rejected because it would persist sensitive calendar-derived work, introduce a new state schema, and still need reconciliation when Exchange coalesces changes for the unchanged key.

### 5. Retain capacity recovery for a bounded sub-batch

A successful sequence reports the page as applied only after all sub-batches complete. `TransactionTooLargeException` from a sub-batch of at most 50 operations retains the existing provider-capacity outcome: at a window greater than one the unchanged checkpoint is retried with a halved ActiveSync window; at window one the run blocks with `CALENDAR_PROVIDER`. Earlier successful sub-batches may remain visible and are repaired by replay.

The fixed operation limit is not presented as Android's maximum byte capacity. It is the product policy requested by this change and intentionally leaves headroom for Binder overhead. Recursive byte-based or failure-based shrinking is outside scope.

### 6. Report aggregate suppression and sub-batch progress

Diagnostics will add allow-listed bounded fields for attendee limit/input/omitted counts, organizer-only suppression, total sub-batches, ordinal, current sub-batch operation count, and cumulative confirmed applied count. Success emits one summary per sub-batch plus the final page summary. Failure identifies the attempted sub-batch and the confirmed prefix without asserting that the failed IPC applied zero operations. No attendee value, organizer value, event content, provider row identifier, SyncKey, or payload is logged.

### 7. Keep validation within the repository's unit-only boundary

Tests will follow RED-GREEN-REFACTOR. Pure planner tests will cover attendee limits and cross-batch reference rewriting. Adapter/gateway fakes will simulate process interruption, ambiguous failure, cancellation, and replay against partial rows. Diagnostics tests will verify only aggregate allow-listed output. The existing Android 16 manual installation check remains the only device-level validation.

## Risks / Trade-offs

- [A page can be temporarily partially visible between sub-batches] → Keep the checkpoint unchanged, stop at the first failure or obsolete fence, and make the next page replay replace every affected child collection deterministically.
- [An event may be visible before its child rows are complete] → Preserve dependency order, keep sub-batches contiguous, and converge on replay; cross-call invisibility is not available through the public provider API.
- [A failed Binder response cannot prove whether the current sub-batch committed] → Count only previously confirmed operations and replay the whole logical page.
- [Organizer-only representation intentionally loses attendee fidelity] → Apply it only above the explicit boundary, preserve semantic response calculation, record aggregate suppression, and restore full attendees if a later list is within the limit.
- [OEM Calendar Provider behavior may impose a lower payload limit even below 50 operations] → Preserve typed capacity handling and unchanged-checkpoint replay rather than increasing the fixed batch size or skipping the event.
- [A bug in reference rewriting could attach children to the wrong event] → Reject unresolved/forward references, validate every insert result, retain owned-calendar predicates, and unit-test boundaries around parent and exception inserts.

## Migration Plan

No persisted schema or checkpoint migration is required. A currently blocked page retains its old SyncKey; after installing the new build, a manual synchronization replays that page under the new attendee and sub-batch rules. Previously committed oversized attendee collections are normalized only when Exchange returns that item again or a later full reset replays it; the release does not force a destructive full synchronization solely for cleanup.

Rollback does not require data migration. An older build can read the same profile, checkpoint, and provider rows, but it may reproduce the original oversized transaction when the large item is returned again.
