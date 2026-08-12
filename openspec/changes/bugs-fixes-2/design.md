## Context

See `proposal.md` for the observed failure. The ActiveSync response has already passed HTTPS, WBXML, command, and page decoding when calendar application-data parsing fails. The exception parser currently invokes the same strict current-user response resolver used when a received meeting series requires classification. That resolver throws when the matching attendee has no `AttendeeStatus`, so an absent optional exception override is incorrectly promoted to critical `PROTOCOL_DATA` before the series response is considered.

The domain model already represents an exception response as `Absent`, `Empty`, or `Value`. The calendar mapper already preserves a prior explicit exception override for a partial change and otherwise resolves `Absent` against the series response. The fix therefore belongs at the ActiveSync application-data parsing boundary and does not require a model, provider, checkpoint, or storage change.

## Goals / Non-Goals

**Goals:**

- Preserve `Absent` for an exception response that cannot be unambiguously derived from optional attendee fields.
- Retain explicit `ResponseType` priority and supported attendee-status-derived exception overrides.
- Keep required received-series classification strict.
- Prove the real ActiveSync 16.1 response shape no longer blocks the page while existing inheritance and atomic checkpoint behavior remain intact.

**Non-Goals:**

- Default any meeting or exception to accepted, tentative, or another manufactured response.
- Skip a malformed item, partially commit a response page, or advance a synchronization key before provider success.
- Change WBXML limits, adaptive window sizing, ActiveSync commands, diagnostics privacy, Calendar Provider representation, or synchronization state schemas.
- Add production dependencies or broaden automated tests beyond local JVM unit tests.

## Decisions

### 1. Separate optional exception inference from required series classification

Keep the existing strict resolver for contexts in which a received meeting series must have a response. Add or expose an optional resolution path that returns no value unless there is exactly one attendee matching the profile email and that attendee has a supported status. The exception parser uses this optional result only when its own `ResponseType` is absent; no result leaves the exception field `Absent`.

This keeps a missing or ambiguous attendee status from becoming an exception override while retaining the existing mapping from supported attendee statuses. An explicit exception `ResponseType` continues to short-circuit attendee fallback.

Alternative: make the existing resolver lenient everywhere. Rejected because an initial received meeting series with no authoritative or attendee-derived response must still fail instead of being presented as an ordinary or accepted event.

Alternative: always ignore exception attendee status and inherit from the series. Rejected because Exchange can supply a useful per-occurrence attendee response when exception `ResponseType` is omitted, and existing behavior and tests preserve that override.

### 2. Leave inheritance and partial-change merge policy in the calendar mapper

The parser will continue to describe what the exception explicitly supplies. It will not receive the series response or previous provider snapshot merely to resolve inheritance. When parsing leaves the exception response `Absent`, the existing mapper remains responsible for preserving a previous explicit override on a partial change or inheriting the current series response when no prior override exists.

Alternative: pass the series response into exception parsing and materialize the inherited value there. Rejected because it would erase the distinction between an explicit override and inherited state, complicate partial-change merging, and duplicate mapper policy.

### 3. Preserve page atomicity and failure classification

The remote calendar continues parsing every command before returning a page with next checkpoints. The corrected exception shape becomes a normal upsert and follows the existing provider-first, checkpoint-second commit path. No item-level skip or retry special case is introduced. If the received series is itself unclassifiable, strict parsing still returns the existing critical protocol-data failure and the checkpoint remains unchanged.

### 4. Reproduce the payload with privacy-safe unit fixtures

Use synthetic addresses, identifiers, and event content in local JVM tests. A failing parser regression will model an ActiveSync 16.1 recurring `Add` whose series has a supported response and whose exception has the current-user attendee but omits both exception `ResponseType` and `AttendeeStatus`. Assertions will cover the absent parsed override and inherited final presentation. Focused regressions will retain explicit `ResponseType`, attendee-derived override, prior explicit override on partial change, and strict series failure. A remote-page regression will prove the same command can return next checkpoints instead of `PROTOCOL_DATA` without recording raw server payloads.

## Risks / Trade-offs

- **[An ambiguous exception attendee list no longer blocks an otherwise classifiable series]** → Limit leniency to optional exception override inference; preserve every supplied attendee row and keep series classification strict.
- **[A future refactor could accidentally use the optional resolver for required classification]** → Give strict and optional entry points distinct names and cover both outcomes with regression tests.
- **[Partial changes could discard a previous explicit exception override]** → Assert that `Absent` continues through parsing and that the existing mapper preserves the prior override.
- **[Diagnostics could expose the problematic event while improving observability]** → Keep current bounded reason codes and exception types; do not add email, subject, attendee, server identifier, or payload logging.

## Migration Plan

No persisted-state or calendar migration is required. Ship the fixed APK in place. A manual synchronization request from the blocked state reuses the last committed checkpoint, re-fetches the uncommitted page, applies it through the normal atomic provider path, and continues pagination. Rolling back is data-compatible but reintroduces the block if the same response shape is encountered again.
