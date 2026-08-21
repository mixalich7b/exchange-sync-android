## Context

See [proposal.md](proposal.md) for the failure motivation and [calendar-sync delta](specs/calendar-sync/spec.md) for the required behavior. The owned calendar represents recurring series with `DTSTART`, `DURATION`, and `RRULE`; `DTEND` is normally SQL `NULL`. `AndroidOwnedCalendarProviderGateway.queryExisting` converts a clean provider row into a `ProviderEvent` so ActiveSync 16.x partial `Change` values can inherit omitted fields.

The current snapshot reader falls back to `DTSTART + DURATION` only when the provider cursor reports `DTEND` as SQL `NULL`. Android Calendar Provider timezone-database maintenance can instead expose the missing recurring end as integer zero while retaining the valid duration. The reader converts zero to `Instant.EPOCH`, the core mapper correctly rejects the merged reversed range, and the page checkpoint remains blocked. Dirty rows already request a fenced full reset and must not enter this normalization path.

The implementation remains inside `:infrastructure`, must retain the pure `:core` mapping policy, and must be verified with local JVM tests only.

## Goals / Non-Goals

**Goals:**

- Restore the canonical end of an otherwise valid clean recurring provider snapshot before it becomes prior state for a partial server merge.
- Gate recovery on independently trustworthy local fields so an epoch timestamp is not treated as a general missing-value marker.
- Preserve the existing core validation and failure behavior for explicit server data, non-recurring rows, dirty rows, malformed durations, and other inconsistent snapshots.
- Keep recovery repeatable if Calendar Provider performs the same maintenance again.

**Non-Goals:**

- Persisting a separate copy of every server event or introducing a new synchronization-state format.
- Mutating Calendar Provider solely to repair old rows before processing a server page.
- General RFC 5545 duration support beyond the provider duration form already written and read by the application.
- Moving Android cursor interpretation or provider-specific sentinels into `:core`.

## Decisions

### 1. Normalize at the Calendar Provider snapshot boundary

`toProviderSnapshot` will resolve the effective local end while the cursor's `DTSTART`, `DTEND`, `DURATION`, and `RRULE` are available together. An epoch-zero `DTEND` will be treated like a missing recurring end only when the same clean row has a present recurrence rule, a present start, and a parseable positive duration. The resolved end will be `start + duration`.

This keeps the workaround at the boundary that produced the artifact. The core mapper will continue to receive ordinary domain timestamps and will continue rejecting every equal or reversed effective range. Normalizing in `CalendarEventMapper` was rejected because it could not distinguish provider maintenance artifacts from explicit invalid ActiveSync values. Adding a second persistent server snapshot was rejected as unnecessary state and migration complexity.

### 2. Keep the recovery predicate narrow and fail closed

The duration parser will accept only the existing `PT<n>S` representation, require a positive value, and reject numeric multiplication, provider epoch-millisecond addition, or instant-addition overflow. Normal SQL `NULL` fallback will retain its current behavior. A clean recurring epoch-zero row whose start or duration cannot produce a trustworthy end will expose no inherited end and will carry an infrastructure-local untrusted-range marker into page planning. A partial change that still lacks a complete range will then follow the existing protocol-data failure path, while a server `Delete` or an explicit valid replacement range remains authoritative. A non-recurring row and any nonzero conflicting `DTEND` will retain the current snapshot interpretation and therefore the existing validation or reset outcome.

Preferring duration for every row that happens to expose both fields was rejected because it would hide provider inconsistencies outside the observed failure. Treating every nonpositive end as missing was rejected because the product retains all server history and valid pre-epoch timestamps must not be globally reclassified.

### 3. Do not weaken response precedence or dirty-row fencing

The normalized value is only prior state. A partial `Change` with an absent `EndTime` may inherit it, while any explicit response `EndTime` still replaces it and passes through the existing time-range validation. An untrustworthy local end is deferred until the server mutation is known so it cannot block a `Delete` or a complete explicit replacement range. The gateway's existing dirty-row detection continues to request a full mirror reset before local user or OEM modifications can be accepted as server truth.

Auto-resetting every affected mirror was rejected because the clean recurring row already contains enough canonical information for lossless recovery. Skipping the event or advancing the checkpoint around it was rejected because it would break the complete mirror and page atomicity guarantees.

### 4. Prove both recovery and trust-boundary guards with focused unit tests

Add a cursor-level regression whose clean recurring row contains `DTEND=0`, a valid `PT<n>S` duration, and a recurrence rule, and assert that the provider snapshot exposes `start + duration`. Add an adapter-level regression that feeds a partial `Change` with omitted times for that existing snapshot and proves the page plans/applies the same identity without `PROTOCOL_DATA`.

Guard regressions will prove that the normalization does not apply to a non-recurring row, does not manufacture an end from invalid or nonpositive duration, and does not replace an explicitly invalid server end. Existing tests remain the authority for dirty-row reset, checkpoint ordering, and ordinary provider `DTEND=NULL` reconstruction.

## Risks / Trade-offs

- [An epoch-zero end could be intentional historical data] → Require a recurrence rule and positive provider duration and normalize only the local provider snapshot; explicit server values remain authoritative and validated.
- [Duration arithmetic can overflow or produce an instant outside Calendar Provider epoch milliseconds] → Use checked conversion/addition, mark the inherited range untrustworthy, and fail closed if the server does not replace it.
- [An OEM emits another duration syntax] → Preserve current parser scope; unsupported syntax is not silently coerced and remains diagnosable.
- [Calendar Provider can repeat the corruption after recovery] → Perform normalization on every clean snapshot read rather than relying on a one-time database repair.
- [A successful later provider update may or may not clear the stored epoch value on every OEM] → Do not depend on that side effect; correctness comes from read-time normalization.

## Migration Plan

No persisted-data or Calendar Provider migration is required. Ship the snapshot normalization in place; an already affected clean recurring row becomes usable on its next partial server change without a manual full reset. Rollback is data-compatible and restores the previous failure behavior without changing checkpoints or provider schema. Before archival, update `docs/calendar-sync.md`, run focused and full verification, and confirm the untracked diagnostic `logs` file remains outside the change.
