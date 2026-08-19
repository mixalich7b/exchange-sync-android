## Why

Current event-validation records identify the failing boundary and exception chain but omit the values needed to distinguish malformed server data from an invalid merge with a prior provider snapshot. Calendar Provider batch failures likewise report aggregate sub-batch progress without exposing which planned inserts and safe values were submitted, so a real device failure cannot be diagnosed from retained Logcat alone.

## What Changes

- Extend failed event parse, mapping, and provider-planning diagnostics with a structured snapshot of the available ActiveSync command, current event, prior event, and derived validation state.
- Permit calendar diagnostic failure records to include actual timestamps, recurrence and timezone data, location, identifiers, status/response flags, reminders, null/empty/presence state, and bounded collection counts.
- Explicitly exclude event subject/title, body/description, every attendee identity or attendee value, and organizer identity or organizer value from both typed diagnostic events and formatted Logcat output.
- On a failed Calendar Provider sub-batch, emit a bounded, correlated description of every attempted operation, its global and sub-batch index, target entity, references, column presence, and all otherwise permitted provider values; attendee and organizer operations retain only structural metadata.
- Keep the existing secret boundary for credentials, headers, cookies, certificate aliases, raw WBXML, raw request/response bodies, and account identifiers, and keep diagnostic logging non-fatal.
- Add unit regressions for complete safe-field output, mandatory redaction, bounded failed-sub-batch detail, correlation, and formatter failure isolation.
- Update developer diagnostics documentation with the new failure-only records and their privacy boundary.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `diagnostic-logging`: expand event-validation and Calendar Provider insertion failure diagnostics with a detailed allow-listed calendar snapshot while excluding people, organizer, title, and description data.

## Impact

- Affected code: infrastructure ActiveSync/calendar validation error context, Calendar Provider batch execution, device diagnostic event models and formatter, plus their local JVM tests.
- Affected specifications and documentation: `diagnostic-logging`, `docs/diagnostics.md`, and any calendar-sync documentation that describes failure investigation.
- No production dependency, remote telemetry, persistence, user-interface, synchronization, checkpoint, retry, or Calendar Provider mutation behavior changes.

## Non-goals

- Logging successful event payloads or successful provider-operation values.
- Logging raw WBXML or unrestricted exception messages as a substitute for structured fields.
- Skipping, repairing, coercing, or partially committing invalid events or failed provider batches.
- Changing the existing page atomicity, checkpoint ordering, failure category, retry, or blocking policy.
