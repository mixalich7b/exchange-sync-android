## Context

See [proposal.md](proposal.md) for the production failure and [the calendar-sync delta](specs/calendar-sync/spec.md) for the required behavior.

ActiveSync response bodies and decoded WBXML documents are both bounded at 2 MiB. The generic decoder currently constructs a complete `WbxmlElement` tree, limits nesting to 32 levels and each inline string to 256 KiB, but stops after 20,000 elements. The observed response is 954,252 bytes (about 932 KiB) and reaches that element limit because a single recurring meeting repeats more than 200 attendees across changed and deleted exceptions. Adaptive `WindowSize` reduction cannot split that response once the window reaches one.

The complete attendee list is currently required by calendar parsing and meeting-response classification. Calendar Provider planning already omits all non-organizer attendee rows when an event or effective exception list contains more than 100 entries, but that policy runs only after WBXML decoding and domain mapping succeed.

The Exchange response is untrusted protocol input and can contain private event and attendee data. Existing byte, depth, string, protocol-validation, and privacy-safe diagnostic boundaries therefore remain part of the design even though the target Android 16 device has ample memory and a short CPU/allocation spike is acceptable.

## Goals / Non-Goals

**Goals:**

- Admit a complete Calendar `Sync` response containing up to 256,000 WBXML elements while keeping decoding deterministic and finite on every device.
- Preserve the current full-tree parser, complete attendee validation, current-user response resolution, exception inheritance, and downstream attendee suppression.
- Preserve adaptive recovery above window one and the existing terminal behavior when a single item exceeds the new budget.
- Cover both the newly accepted high-element shape and the retained over-capacity path with local unit tests.

**Non-Goals:**

- Making capacity depend on runtime RAM, Android heap class, server identity, or synchronization trigger.
- Adding a streaming parser, a selective attendee representation, payload persistence, raw-WBXML diagnostics, or a fallback that skips the item.
- Expanding any limit other than WBXML element count or changing Calendar Provider materialization policy.

## Decisions

### Raise the finite default element budget to 256,000

`WbxmlLimits.maxElements` will use 256,000 as its default instead of 20,000. The reader will continue to count every encountered WBXML element and fail immediately on element 256,001. The same limit object is used by the writer; production request trees are fixed and small, while the unchanged 2 MiB output limit remains the effective writer bound.

The value gives the observed high-fanout meeting more than an order of magnitude of headroom while retaining a clear allocation and CPU ceiling. It is fixed rather than calculated from device memory so the same response has the same result on every supported Android 16 device and in unit tests.

Alternatives considered:

- Removing the element limit was rejected because a small byte-bounded document can still contain a pathological number of empty sibling elements.
- Selecting a runtime limit from available memory was rejected because it would make synchronization and checkpoint behavior device-dependent.
- Raising the 2 MiB response/document limits was rejected because the observed item already fits them and doing so would enlarge a separate trust boundary unnecessarily.

### Retain complete `WbxmlElement` materialization

The Calendar response will continue through the existing generic reader and application-data parser. All attendee entries and exception fields are parsed before the existing Calendar Provider planner chooses full or organizer-only attendee representation. No new domain state is introduced.

This is intentionally simpler than streaming or semantic compaction. The byte bound limits retained text, the new element bound limits object count, and the target device can tolerate the short-lived allocation. Keeping the existing representation also avoids changing partial `Change` merging, exception attendee inheritance, optional exception response fallback, and ambiguity detection for the configured user's attendee entry.

Alternatives considered:

- A streaming Calendar-specific parser was rejected because it would duplicate WBXML structure handling and introduce a second parsing path for a capacity that the target device can safely materialize.
- An `OversizedAttendees` summary was rejected because it would cross the ActiveSync, core mapping, provider-planning, merge, and diagnostic boundaries even though the current full list already provides the required semantics.
- Skipping the ServerId and committing its returned key was rejected because it would permanently create a hole in the one-way mirror.

### Exercise the actual valid shape before changing production behavior

Test-first implementation will add a compact generated Calendar `Sync` fixture with one recurring item, more than 200 attendees, and enough changed and deleted exceptions to exceed the old 20,000-element budget while remaining below every new bound. Tests at existing unit seams will demonstrate that the response decodes, preserves the exception set and response inputs, reaches attendee suppression, and remains eligible for checkpoint commit.

Existing capacity fixtures that relied on 20,001 elements will be moved above the new default budget. They will continue to verify window reduction, unchanged checkpoint replay, and terminal `PROTOCOL_DATA` at window one. Small custom-limit codec tests will continue to verify the exact off-by-one reader boundary without requiring every low-level test to allocate the production maximum.

No production diagnostic field changes are required. A successful item already emits response size, decoded command counts, provider planning/suppression summaries, and checkpoint commit; a true element-capacity failure already emits `wbxml_element_count` with window reduction or minimum-window block.

## Risks / Trade-offs

- **Higher transient heap use and allocation churn for element-dense responses** → Keep the 2 MiB document, 256,000-element, depth, and inline-string limits; verify the generated regression and, when the server item is available, manually confirm synchronization on the target Android 16 device.
- **More CPU can be spent before malformed high-element structure is rejected** → Preserve the finite element ceiling and all existing structural validation; do not retry a structurally malformed response as a capacity recovery.
- **The larger generated default-capacity fixture can slow unit tests** → Use compact generated values, keep exact low-limit boundary tests small, and reserve the production-sized fixture for the remote capacity paths that depend on the default.
- **A future valid item can still exceed 256,000 elements** → Preserve checkpoint safety and the current user-actionable capacity problem; reconsider streaming or semantic compaction only with new evidence rather than silently raising all protocol bounds.

## Migration Plan

There is no persisted-data or schema migration. After installing the updated APK, a manual retry reuses the unchanged committed synchronization key and requests the blocked item again. Successful application commits the returned key through the existing checkpoint path.

Rollback requires no data conversion. An older build can read the same profile, calendar, and synchronization state, although it will block again if Exchange replays an item above its smaller element budget.
