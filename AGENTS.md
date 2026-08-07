# Repository instructions

## Sources of truth

- `openspec/specs/` describes the currently accepted system behavior.
- `openspec/changes/<change-name>/` describes an in-progress change.
- For OpenSpec-managed changes, its `proposal.md`, `specs/`, `design.md`,
  and `tasks.md` are the canonical planning artifacts.
- Do not create a parallel implementation plan or design document when the
  corresponding OpenSpec artifacts already exist.
- When implementation discoveries change the intended behavior or design,
  update the OpenSpec artifacts before continuing.

## OpenSpec and Superpowers integration

- Use OpenSpec for requirements, scenarios, design decisions, and task tracking.
- Use Superpowers for worktree isolation, TDD, systematic debugging,
  code review, and verification.
- Superpowers brainstorming may be used while exploring the problem, but its
  conclusions must be written into the active OpenSpec change.
- Superpowers writing-plans must not create a second competing task plan.
  Refine `openspec/changes/<change>/tasks.md` instead.
- During implementation, work through the active OpenSpec tasks in order and
  update their completion state.
- Explicitly invoke relevant skills when automatic activation is uncertain.

## Required workflow for non-trivial changes

1. Explore the problem using `$openspec-explore` when requirements are unclear.
2. Create or update the change using `$openspec-propose`.
3. Review proposal, specs, design, and tasks before modifying production code.
4. Use an isolated branch or worktree for implementation.
5. Implement using `$test-driven-development`.
6. Use `$systematic-debugging` for unexpected behavior instead of speculative fixes.
7. Run all relevant tests, linting, type checks, and builds.
8. Validate the implementation with `$openspec-verify-change`.
9. Run Codex `/review` against the resulting diff.
10. Sync accepted specifications and archive the completed change.

## Small-change exception

OpenSpec may be skipped for:

- Typographical fixes
- Documentation-only corrections
- Mechanical formatting changes
- Trivial dependency metadata updates
- Small test-only refactoring with no behavioral change

Bug fixes that change observable behavior should normally have an OpenSpec
change and must include a regression test.

## Completion criteria

A task is not complete until:

- Relevant tests pass
- Lint and type checks pass
- The diff contains no unrelated modifications
- Behavior matches the OpenSpec scenarios
- Documentation and configuration are updated when needed
- No secrets or generated local files are included

