## 1. Regression Tests

- [ ] 1.1 Add a failing cursor-level unit regression for a clean recurring provider row with `DTEND=0`, a valid positive `PT<n>S` duration, and `RRULE`, asserting that its snapshot end is reconstructed as `DTSTART + DURATION`.
- [ ] 1.2 Add failing guard regressions showing that epoch-zero normalization is not applied to non-recurring rows or recurring rows with absent, malformed, zero, negative, or overflowing durations, while the existing SQL-`NULL` recurring-end reconstruction remains covered.
- [ ] 1.3 Add a failing adapter-level regression where a partial ActiveSync `Change` omits start and end for the affected recurring identity, asserting that the page applies successfully and remains eligible for checkpoint commit; cover that an explicitly invalid server end still rejects the page.

## 2. Snapshot Normalization

- [ ] 2.1 Implement the minimal `AndroidOwnedCalendarProviderGateway` snapshot-end resolver that treats epoch zero like an absent end only for a clean recurring row with a present start and parseable positive provider duration.
- [ ] 2.2 Make duration parsing and `DTSTART + DURATION` arithmetic fail closed on invalid input or overflow without changing explicit ActiveSync value precedence, dirty-row fencing, page atomicity, or non-recurring behavior.
- [ ] 2.3 Run the focused `:infrastructure:testDebugUnitTest` suite, make the new and existing regressions pass, and refactor the boundary code without expanding its behavior.

## 3. Documentation and Completion Checks

- [ ] 3.1 Update `docs/calendar-sync.md` to document the recurring provider-snapshot normalization, its tzdb-maintenance motivation, and the limits that preserve server-data validation.
- [ ] 3.2 Run `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap`, resolving any regression attributable to this change.
- [ ] 3.3 Inspect the final tracked diff and repository status to confirm that changes are scoped to this fix, no generated files or secrets are included, and the untracked diagnostic `logs` file remains excluded.
- [ ] 3.4 Validate the implementation with `$openspec-verify-change`, run Codex `/review` against the resulting diff, and resolve all applicable findings before specification sync and archival.
