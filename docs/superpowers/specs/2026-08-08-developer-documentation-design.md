# Developer documentation design

## Context

The repository has current behavioral specifications in `openspec/specs/`, historical planning artifacts in `openspec/changes/archive/`, user-facing product information in `README.md`, and detailed developer instructions in `AGENTS.md`. It does not yet have a maintained developer-oriented explanation of the implemented system.

The new documentation must describe the current implementation without becoming a second normative specification. When documentation and accepted OpenSpec requirements disagree, `openspec/specs/` remains authoritative.

## Documentation structure

Create three focused documents:

- `docs/README.md` defines the audience, explains the relationship between documentation, OpenSpec, and archived design artifacts, and links to the detailed documents.
- `docs/architecture.md` records the currently implemented product boundary, module responsibilities, permitted dependencies, application composition, and major data flows.
- `docs/connection.md` explains the implemented connection profile, validation, Android KeyChain integration, validate-probe-commit flow, TLS trust composition, ActiveSync capability probe, persistence, error categories, and accepted limitations.

The documents describe only implemented behavior. Planned calendar synchronization, background work, reminders, and notifications are identified as outside the current boundary rather than documented as available architecture.

## Maintenance rule

Update `AGENTS.md` so every OpenSpec archive workflow includes a documentation audit immediately before archiving. The audit must:

1. identify documentation affected by the completed change;
2. update `docs/` to the post-change implemented state;
3. remove or correct stale statements;
4. preserve the distinction between normative `openspec/specs/`, explanatory current documentation, and historical archived artifacts;
5. prevent archive completion while affected documentation is knowingly stale.

The rule belongs in both the sources-of-truth guidance and the required OpenSpec workflow so its authority and execution point are unambiguous.

## Content boundaries

The developer documentation may summarize accepted requirements and important design trade-offs, but it must not duplicate every scenario or implementation task. It must contain no credentials, private endpoints, certificate material, or machine-local values. Build commands remain canonical in `AGENTS.md`; developer documents link there instead of maintaining a second command list.

## Verification

Because the change is documentation-only, verification consists of Markdown inspection, link and path checks, placeholder and secret scans, `git diff --check`, and confirmation that every documented statement matches the current main specs and production structure. No Gradle execution is required unless documentation work changes build or source files unexpectedly.
