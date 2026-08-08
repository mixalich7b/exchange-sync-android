# Developer Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a current developer documentation snapshot and make its update a mandatory gate immediately before archiving every OpenSpec change.

**Architecture:** Keep normative behavior in `openspec/specs/`, explanatory current-state documentation in three focused files under `docs/`, and historical decisions in archived OpenSpec changes. Update `AGENTS.md` so archive workflows reconcile affected developer documentation with the final implementation and accepted specs before moving a change to the archive.

**Tech Stack:** Markdown, OpenSpec 1.8.0, shell-based documentation checks, Git.

## Global Constraints

- Documentation is for project developers and maintainers; do not add a user guide.
- Describe only behavior and architecture implemented in the current checkout.
- Identify calendar synchronization, background work, reminders, and notifications as outside the implemented boundary.
- `openspec/specs/` remains normative when documentation disagrees with a specification.
- Do not include credentials, private endpoints, certificate contents, private keys, or machine-local paths.
- Keep build and verification commands canonical in `AGENTS.md`; link to it instead of duplicating the command list.
- This documentation-only change uses the repository small-change exception and does not create an OpenSpec change.

---

### Task 1: Current developer documentation snapshot

**Files:**
- Create: `docs/README.md`
- Create: `docs/architecture.md`
- Create: `docs/connection.md`
- Reference: `openspec/specs/project-bootstrap/spec.md`
- Reference: `openspec/specs/connection-settings/spec.md`
- Reference: `openspec/changes/archive/2026-08-08-connection/design.md`
- Reference: `settings.gradle.kts`
- Reference: `app/src/main/kotlin/net/mixalich7b/exchangesync/AppContainer.kt`
- Reference: `app/src/main/kotlin/net/mixalich7b/exchangesync/MainActivity.kt`

**Interfaces:**
- Consumes: accepted main specs, current production module structure, and archived design rationale.
- Produces: a developer documentation index, an architecture snapshot, and a detailed connection-capability explanation.

- [x] **Step 1: Confirm the documentation inputs and current boundary**

Run:

```bash
test -f openspec/specs/project-bootstrap/spec.md
test -f openspec/specs/connection-settings/spec.md
test -f openspec/changes/archive/2026-08-08-connection/design.md
rg -n '^include\(' settings.gradle.kts
rg -n 'CalendarContract|WorkManager|NotificationManager|AlarmManager' app core feature infrastructure --glob '!**/build/**'
```

Expected: all three source documents exist; the four modules are listed; the final search returns no product implementation matches.

- [x] **Step 2: Create the documentation index**

Write `docs/README.md` in Russian with these sections and meanings:

```markdown
# Документация для разработчика

## Назначение
Explain that this directory describes the currently implemented system for maintainers and is not a user guide.

## Источники истины
State that openspec/specs is normative, docs is explanatory current state, archived changes are historical context, and production code is inspected for implementation details.

## Документы
Link to architecture.md and connection.md with one-sentence descriptions.

## Поддержание актуальности
State that affected documents are reconciled immediately before each OpenSpec archive and refer to AGENTS.md for the mandatory workflow.
```

- [x] **Step 3: Create the architecture snapshot**

Write `docs/architecture.md` in Russian with:

- current boundary: connection configuration and verification only;
- module graph `:app -> :feature:settings -> :core` and `:app -> :infrastructure -> :core`;
- responsibilities for all four modules;
- manual composition in `AppContainer` without a dependency-injection framework;
- six-step validate-probe-commit flow from UI draft through atomic persistence;
- Android boundaries: KeyChain and Compose lifecycle wiring live outside `core`;
- concurrency boundaries: KeyChain and TLS transport setup run away from Main, while synchronous TLS setup has no hard deadline;
- persistence boundary: one DataStore profile containing email, `domain\login`, hostname, and KeyChain alias only;
- automated verification boundary: JVM unit tests, compilation, lint, and debug assembly;
- explicitly unimplemented behavior: calendar discovery/synchronization, Calendar Provider writes, WorkManager scheduling, reminders, and notifications.

- [x] **Step 4: Create the connection-capability snapshot**

Write `docs/connection.md` in Russian with:

- the four profile values and fixed HTTPS port 443;
- initial loading lock and local validation rules;
- Android KeyChain alias selection and private-key custody;
- atomic validate-probe-commit semantics and previous-profile preservation;
- exact initial `OPTIONS https://<hostname>:443/Microsoft-Server-ActiveSync` request;
- explicit HTTPS redirect handling, cross-host support, loop protection, and five-redirect limit;
- required terminal HTTP 200, supported protocol versions, and `FolderSync` plus `Sync` commands;
- combined Android-system and optional ignored local-CA trust without trust-all or custom hostname bypass;
- structured `NO_TRUST_ANCHOR` classification and the accepted priority of missing/invalid local CA diagnostics;
- selected client-certificate use verification in the terminal TLS handshake;
- stable error-category groups without stack traces or raw key material;
- accepted limitations: no live-server automated tests, no strict TLS setup deadline, no calendar-access proof from `OPTIONS`, and no authentication password.

- [x] **Step 5: Verify the documentation set**

Run:

```bash
test -f docs/README.md
test -f docs/architecture.md
test -f docs/connection.md
rg -n 'architecture\.md|connection\.md' docs/README.md
rg -n 'реализован|не реализован|openspec/specs' docs/README.md docs/architecture.md docs/connection.md
git diff --check -- docs/README.md docs/architecture.md docs/connection.md
```

Expected: every file and index link exists, current/unimplemented boundaries and spec authority are explicit, and the diff check exits successfully.

- [ ] **Step 6: Commit the current documentation snapshot**

```bash
git add docs/README.md docs/architecture.md docs/connection.md docs/superpowers/plans/2026-08-08-developer-documentation.md
git commit -m "docs: add current developer documentation"
```

### Task 2: OpenSpec archive documentation gate

**Files:**
- Modify: `AGENTS.md` under `Sources of truth`, `Required workflow for non-trivial changes`, and `Completion criteria`
- Reference: `docs/README.md`

**Interfaces:**
- Consumes: the documentation roles established by Task 1.
- Produces: a mandatory archive-time audit that keeps affected developer documentation aligned with accepted specs and implementation.

- [ ] **Step 1: Add the documentation source role**

Under `Sources of truth`, add rules stating:

```markdown
- `docs/` explains the currently implemented architecture and capabilities for developers; it is not normative and must agree with `openspec/specs/`.
- Archived OpenSpec changes preserve historical decisions and must not be treated as the current-state documentation.
```

- [ ] **Step 2: Add the archive-time documentation gate**

Immediately before the sync/archive step in `Required workflow for non-trivial changes`, require the agent to:

```markdown
Update every affected file under `docs/` to match the final implementation and accepted main specs, remove stale statements, and verify that planned behavior is not presented as implemented. Do not archive while affected developer documentation is knowingly stale.
```

Keep specification sync after the documentation audit, then archive the change.

- [ ] **Step 3: Strengthen completion criteria**

Replace the generic documentation completion item with an explicit criterion that affected developer documentation reflects the post-change implemented state and contains no stale or prematurely documented behavior.

- [ ] **Step 4: Verify placement and wording**

Run:

```bash
rg -n 'docs/|archive|archiv|stale|implemented state|реализ' AGENTS.md
git diff --check -- AGENTS.md
```

Expected: `docs/` has a defined non-normative role, the audit appears before archive, stale documentation blocks completion, and the diff check exits successfully.

- [ ] **Step 5: Commit the archive documentation gate**

```bash
git add AGENTS.md
git commit -m "docs: require documentation audit before archive"
```

### Task 3: Final documentation audit

**Files:**
- Verify: `docs/README.md`
- Verify: `docs/architecture.md`
- Verify: `docs/connection.md`
- Verify: `AGENTS.md`
- Verify: `openspec/specs/project-bootstrap/spec.md`
- Verify: `openspec/specs/connection-settings/spec.md`

**Interfaces:**
- Consumes: documentation and archive rule from Tasks 1 and 2.
- Produces: evidence that the documentation is coherent, safe, linked, and compatible with valid main specs.

- [ ] **Step 1: Inspect all documentation as one current-state snapshot**

Read the four changed documentation files and compare every behavior statement with the two main specs and the production module tree. Correct contradictions before continuing.

- [ ] **Step 2: Scan for unfinished or sensitive content**

Run:

```bash
if rg -n 'T[B]D|T[O]DO|F[I]XME|BEGIN (RSA |EC |ENCRYPTED )?PRIVATE KEY|BEGIN CERTIFICATE' docs/README.md docs/architecture.md docs/connection.md AGENTS.md; then
  exit 1
fi
```

Expected: no matches.

- [ ] **Step 3: Validate OpenSpec and Markdown hygiene**

Run:

```bash
openspec validate --specs --strict
git diff --check
```

Expected: both main specs pass strict validation and the repository diff contains no whitespace errors.

- [ ] **Step 4: Inspect scope and repository state**

Run:

```bash
git status --short
git log -3 --oneline
```

Expected: only the intended documentation commits follow the approved design commit; no source, build, certificate, credential, endpoint, or generated file was added.
