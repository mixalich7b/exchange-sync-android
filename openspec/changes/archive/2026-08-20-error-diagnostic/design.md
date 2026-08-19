## Context

See [proposal.md](proposal.md) for the observed diagnostic gap. The current diagnostics model has one broad nullable event type plus narrow formatter branches for capacity and provider sub-batches. ActiveSync parsing already retains command kind and `ServerId`, calendar mapping wraps failures with `ServerId`, and provider application retains the active `CalendarProviderSubBatch`, but none of those boundaries retains a structured, privacy-filtered description of the data that caused the failure.

The implementation must remain local to `:infrastructure` except for making core calendar-mapping failures expose a stable typed rule. It must preserve the existing unit-only test boundary, 3,000-character Logcat record limit, non-fatal logging behavior, page atomicity, and provider-first/checkpoint-second ordering.

## Goals / Non-Goals

**Goals:**

- Make event parse, merge, mapping, representation, and provider-application failures diagnosable from one retained correlated Logcat sequence.
- Preserve every currently available calendar/provider value that the accepted privacy boundary permits, including actual temporal and recurrence values.
- Make forbidden values impossible to reach the formatter through the normal typed path.
- Preserve exact uncertainty at the Binder boundary: describe attempted operations without asserting which operation failed or whether the failed call applied.

**Non-Goals:**

- A general-purpose event serializer, payload dump, remote telemetry channel, or persistent diagnostic store.
- Logging detailed values on successful synchronization paths.
- Inferring, correcting, skipping, or retrying individual invalid events or provider operations.
- Changing user-visible failure classification or exposing diagnostic detail in the UI.

## Decisions

### 1. Use typed failure snapshots with an exhaustive field policy

Add dedicated diagnostic models for a calendar validation snapshot and a provider-operation snapshot rather than adding dozens of nullable scalar properties to the general diagnostic event. Snapshot fields will use stable enum keys and bounded value variants for absence/empty/value state, strings, integers, booleans, timestamps, enum names, reference identities, relationships, and counts. The formatter will have explicit snapshot-only branches and deterministic record/chunk ordinals.

An exhaustive projector will classify every current ActiveSync and Calendar Provider field as:

- full value: identifiers, location, time, all-day, timezone, recurrence, exception identity, meeting/response state, availability, sensitivity, reminder, and technical provider values;
- presence or count only: subject/title, body/description, attendee collections, and organizer fields;
- forbidden: raw WBXML/application payload containers and any value covered by the existing secret boundary.

Attendee and organizer operations will expose operation structure, indexes, reference kind, column names, and bounded counts but no submitted values. Unknown provider value types will be represented by a stable type marker, never arbitrary `toString()` output. New calendar/provider fields will require an explicit policy branch before they can be logged.

An unrecognized provider column will default to one anonymous structural-only entry without retaining its wire name or value. An independently derived completeness test will compare every current `CalendarProviderField` constant with the explicit diagnostic policy, so a newly introduced internal field still requires review instead of silently remaining on the fallback.

This is preferred to a generic map plus a deny-list because a newly added field could otherwise become loggable without privacy review. Reusing exception messages was rejected because messages are unstable, incomplete, and can contain payload data.

### 2. Carry stable validation rules and the nearest safe context

Core calendar validation will replace message-only `CalendarMappingException` construction with a stable typed rule while retaining a developer-readable message and cause behavior. This keeps the rule beside the domain policy that detects it without introducing an infrastructure dependency into `:core`.

At the ActiveSync application-data boundary, a failed Add or Change will attach a projected safe command snapshot to the protocol-data failure while the raw command tree is still available. The projector will traverse only explicitly classified calendar tags, skip forbidden subtrees, and sanitize/limit individual allowed values. If parsing fails on an allowed scalar, the bounded sanitized source value can be retained; the raw tree itself cannot leave the parser boundary.

Value parsing will propagate its typed validation reason and nearest safe failing field rather than collapsing recurrence, attendee, meeting-response, timezone, or all-day failures into a generic value error. Attendee failures may retain the affected collection index, structural presence of the selected attendee subfields, and a bounded current-user match count, but never any attendee value.

At calendar mapping/planning, the failure context will include the incoming mutation and prior provider snapshot already available to `CalendarPagePlanner`. The projector will label response, prior, and effective values separately and compute diagnostic-only relationships such as `before`, `equal`, `after`, and `not_comparable`. For nested exception validation, the mapper will retain the failed exception index/path so diagnostics describe the affected exception plus bounded collection counts rather than dumping every unrelated exception.

Mapping exception messages into enum rules after the fact was rejected because text changes would silently break diagnostics. Storing full domain events in throwable objects was rejected because it would place forbidden narrative and people data in the exception graph.

### 3. Describe the whole failed provider call from the retained sub-batch plan

The adapter already retains `activeSubBatch` until a provider call is confirmed. On any provider-call failure it will first emit the existing aggregate failure record, then project every operation from that retained sub-batch into correlated detail records. Each operation will retain:

- sub-batch ordinal and count;
- global operation index and index within the sub-batch;
- operation kind and target entity;
- existing-row or back-reference kind and its technical identifier when present;
- complete column presence and every permitted submitted value;
- the same provider call outcome and typed provider failure cause as the aggregate record.

The platform does not reliably expose the failing `applyBatch` operation index. Diagnostics will therefore label every detail as attempted and the provider-call outcome as unknown, never select one operation as causal. Operations confirmed by previous sub-batches remain represented only by the existing cumulative count.

Failures while validating or constructing the Android provider request before `applyBatch` are not provider-call failures. The gateway will propagate an explicit not-dispatched state; diagnostics will retain the same privacy-filtered plan as `unsubmitted_operation`, use a zero attempted-call count, and omit `provider_call_outcome`. For an actually dispatched call with an unknown outcome, `applied_operation_count` will be omitted and only the separately named cumulative `confirmed_operation_count` will retain earlier successful sub-batches.

Logging only an exception-reported index was rejected because `RemoteException`, OEM runtime failures, and ambiguous Binder termination do not supply one. Logging the entire unfiltered operation map was rejected because event, attendee, and organizer values share that map.

### 4. Chunk structured details without dropping permitted fields

Each field value will be sanitized and length-bounded before record assembly. Operation and event snapshots will be deterministically split into records below the existing Logcat record limit, with snapshot/operation identity plus chunk ordinal and chunk count repeated on every record. Collection sizes, exception indexes, sub-batch size, and the known maximum of 50 provider operations bound record production. A validation failure emits the affected series or exception detail and counts for unrelated collection members.

The throwable class/cause graph will also have its own aggregate character bound and bounded traversal queue/attempt count in addition to its object and frame-count bounds, so a maximal, cyclic, or wide suppressed graph cannot consume the repeated snapshot header and suppress every detail record.

Blindly truncating one assembled record was rejected because the field needed to diagnose a failure could disappear based on map iteration order. One record per field was rejected because it would create excessive Logcat noise for ordinary event inserts.

### 5. Keep detailed logging failure-only and non-fatal

Safe projection and formatting will run only from event-validation, provider-planning, or provider-call failure handlers. Successful page, map, attendee-suppression, sub-batch, and checkpoint summaries keep their existing aggregate formatter paths. Projection, chunking, formatting, and sink failures remain isolated so they cannot replace the original synchronization outcome or advance/rollback a checkpoint.

## Risks / Trade-offs

- [Allowed location, UID, timezone, recurrence, or identifier values can still contain unexpected personal text] → Apply the existing email/account/header/URL sanitizer to every string and enforce an individual length bound before chunking.
- [Detailed failure logging can produce many records for a 50-operation sub-batch] → Emit details only on failure, pack fields deterministically into bounded chunks, and retain the existing sub-batch limit as the operation-count bound.
- [A formatter or projector omission could hide a newly introduced field] → Use exhaustive sealed/enum mappings and unit tests that enumerate all current domain fields and provider operation variants.
- [A future field could be logged without recognizing that it contains attendee, organizer, title, or description data] → Default new or unknown fields to structural/type-only output until their classification is explicitly reviewed.
- [Provider diagnostics can show every attempted operation but not the causal operation] → Preserve the unknown call outcome and state this limitation in both records and documentation.
- [Changing mapping exceptions to typed rules expands core public API] → Keep the enum narrowly scoped to existing validation rules and verify explicit-API compilation with `:core:test`.

## Migration Plan

No persisted-data or Calendar Provider migration is required. Ship the diagnostic extension in place; after reproducing a failure, collect the new correlated records through the existing Logcat tag. Rollback is data-compatible and only removes the additional failure detail. Implementation completion requires updating `docs/diagnostics.md` and any affected calendar-sync explanation before OpenSpec archival.
