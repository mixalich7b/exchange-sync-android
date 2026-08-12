## Why

A valid single recurring Exchange event with more than 200 attendees repeated across changed and cancelled occurrences can produce a Calendar `Sync` response of 954,252 bytes (about 932 KiB) and more than 20,000 WBXML elements. Because `WindowSize=1` cannot split one item, the current decoder limit blocks synchronization before the existing oversized-attendee suppression can preserve the event in the Android calendar.

## What Changes

- Increase the finite WBXML element budget for full-tree decoding so a large but still document-bounded recurring calendar item can reach normal calendar parsing and provider materialization.
- Keep the existing 2 MiB document, nesting-depth, inline-string, adaptive page-sizing, checkpoint, and terminal-capacity protections.
- Continue parsing the complete attendee and recurrence-exception data before applying the existing 100-attendee Calendar Provider representation limit.
- Add unit regression coverage for a valid high-element recurring event and for the retained failure behavior above the new element budget.
- Update affected developer documentation to describe the enlarged bounded capacity and the handling of a large single item.

## Non-goals

- Replacing the full `WbxmlElement` tree with a streaming or selective Calendar parser.
- Raising the WBXML document-size, nesting-depth, inline-string, HTTP-body, or Calendar Provider sub-batch limits.
- Changing the 100 non-organizer attendee materialization limit or the organizer-only representation of oversized attendee lists.
- Skipping an oversized server item, advancing its synchronization key without applying it, or introducing a second Exchange protocol.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `calendar-sync`: A valid document-bounded single recurring item with large attendee lists and many changed or cancelled occurrences must decode and synchronize when it fits the enlarged finite WBXML element budget, while responses above that budget retain the existing adaptive and terminal behavior.

## Impact

- `:infrastructure` WBXML limits and ActiveSync capacity regression fixtures.
- Existing Calendar parsing, attendee suppression, recurrence-exception mapping, synchronization checkpoint, and diagnostic behavior are exercised but keep their current public contracts.
- `openspec/specs/calendar-sync/` and `docs/calendar-sync.md` require synchronization with the accepted behavior.
- No new production dependency, module, Android permission, network API, persistence format, or project-module dependency is introduced.
