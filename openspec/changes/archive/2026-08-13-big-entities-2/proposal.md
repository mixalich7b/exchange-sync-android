## Why

A valid single recurring Exchange event with more than 200 attendees repeated across changed and cancelled occurrences can produce a Calendar `Sync` response of 954,252 bytes (about 932 KiB) and more than 20,000 WBXML elements. Because `WindowSize=1` cannot split one item, the current decoder limit blocks synchronization before the existing oversized-attendee suppression can preserve the event in the Android calendar.

Device verification after enlarging that decoder boundary exposed the next failure in the same item path: Calendar Provider accepted five bounded sub-batches and then rejected the sixth with `SQLiteConstraintException`. ActiveSync permits an empty exception child to clear that property instead of inheriting the series value, while the current generic provider writer serializes every `ActiveSyncField.Empty` as SQL `NULL`. That is invalid for required Calendar Provider columns such as access level, availability, and self-attendee status, which have non-null platform defaults.

## What Changes

- Increase the finite WBXML element budget for full-tree decoding so a large but still document-bounded recurring calendar item can reach normal calendar parsing and provider materialization.
- Keep the existing 2 MiB document, nesting-depth, inline-string, adaptive page-sizing, checkpoint, and terminal-capacity protections.
- Continue parsing the complete attendee and recurrence-exception data before applying the existing 100-attendee Calendar Provider representation limit.
- Add unit regression coverage for a valid high-element recurring event and for the retained failure behavior above the new element budget.
- Add a RED Calendar Provider regression for a recurrence exception that explicitly clears a supported property backed by a required provider column.
- Encode explicit clears according to each provider column's nullability: use its defined non-null default where that preserves the ActiveSync clear semantics, retain SQL `NULL` only for nullable columns, and reject an unrepresentable required value before calling Calendar Provider.
- Update affected developer documentation to describe the enlarged bounded capacity and the handling of a large single item.

## Non-goals

- Replacing the full `WbxmlElement` tree with a streaming or selective Calendar parser.
- Raising the WBXML document-size, nesting-depth, inline-string, HTTP-body, or Calendar Provider sub-batch limits.
- Changing the 100 non-organizer attendee materialization limit or the organizer-only representation of oversized attendee lists.
- Changing self-attendee row lifecycle, adding OEM-specific organizer-collision handling, or expanding Calendar Provider exception diagnostics.
- Skipping an oversized server item, advancing its synchronization key without applying it, or introducing a second Exchange protocol.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `calendar-sync`: A valid document-bounded single recurring item with large attendee lists and many changed or cancelled occurrences must decode and synchronize when it fits the enlarged finite WBXML element budget; explicit exception property clears must remain representable without writing `NULL` to required Calendar Provider columns, while responses above the decoder budget retain the existing adaptive and terminal behavior.

## Impact

- `:infrastructure` WBXML limits, ActiveSync capacity regression fixtures, and Calendar Provider value planning.
- Existing Calendar parsing, attendee suppression, recurrence-exception mapping, synchronization checkpoint, and diagnostic behavior are exercised but keep their current public contracts.
- `openspec/specs/calendar-sync/` and `docs/calendar-sync.md` require synchronization with the accepted behavior.
- No new production dependency, module, Android permission, network API, persistence format, or project-module dependency is introduced.
