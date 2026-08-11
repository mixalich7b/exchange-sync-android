## Why

A single Exchange meeting with several thousand attendees currently expands into thousands of `ContentProviderOperation` values and exceeds Android's Binder transaction capacity, permanently blocking synchronization at `WindowSize=1`. The mirror needs a bounded local representation for unusually large attendee lists and bounded Calendar Provider writes that can recover deterministically after any interrupted sub-batch.

## What Changes

- **BREAKING**: When an Exchange event or recurrence exception has more than 100 effective attendees, the Android mirror omits its attendee list and retains only the meeting organizer representation; events with 100 or fewer attendees continue to preserve all attendees.
- **BREAKING**: Replace the all-or-nothing Calendar Provider page transaction with dependency-ordered sub-batches containing at most 50 provider operations each. A page can therefore be temporarily partially visible if processing stops between sub-batches.
- Retain the last committed ActiveSync checkpoint until every sub-batch for the page succeeds. Replaying the unchanged page must remove or overwrite partial child state and converge without duplicate events, organizers, attendees, reminders, or recurrence exceptions.
- Preserve the existing fenced owned-calendar boundary, cancellation checks between provider calls, and provider-capacity failure classification when even a bounded sub-batch fails.
- Extend privacy-safe diagnostics to report attendee suppression and sub-batch progress without logging attendee identities or event content.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `calendar-sync`: Bound attendee materialization, split Calendar Provider page writes into dependency-safe sub-batches, and redefine page consistency around replay convergence rather than one provider transaction.
- `diagnostic-logging`: Report aggregate attendee-suppression and provider sub-batch outcomes needed to diagnose bounded large-entity processing.

## Impact

- Affected code: calendar mapping/planning, provider batch planning and Android application, provider operation references/results, synchronization cancellation/capacity handling, and sanitized diagnostics in `:infrastructure`; shared outcome contracts change only if required by the final adapter boundary.
- Affected tests: pure mapper/planner tests, Android-local adapter/gateway fakes, synchronization checkpoint/replay regressions, and diagnostic formatter tests. No instrumentation or end-to-end test suite is introduced.
- Affected documentation: `docs/calendar-sync.md`, `docs/diagnostics.md`, and any architecture text that currently promises complete attendee fidelity or atomic whole-page application.
- No new production dependencies, Android permissions, network behavior, Exchange commands, account integrations, or modules are introduced.

## Non-goals

- Persisting the omitted attendee identities, uploading calendar changes, or adding a UI for expanding large attendee lists.
- Dynamically deriving the attendee or sub-batch limits from Binder size, device manufacturer, memory pressure, or server configuration.
- Silently skipping an entire oversized event, advancing its checkpoint before all planned sub-batches succeed, or weakening owned-calendar predicates.
- Providing cross-call database atomicity that Android Calendar Provider does not expose through its public API.
