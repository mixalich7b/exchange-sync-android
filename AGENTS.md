# Repository instructions

## Product summary and current boundary

The product is an Android 16 application that will synchronize the primary
calendar from a private Microsoft Exchange server into the device calendar.
The planned protocol is Exchange ActiveSync over HTTPS on port 443 with mTLS;
the application must implement the integration itself and must not register or
use Android's built-in Exchange account integration.

The explored first product stage has these constraints:

- One locally configured profile with email address, `domain\\login`, server
  hostname, and a client-certificate alias selected from Android's installed
  VPN and app certificates.
- One-way, read-only synchronization from Exchange to Android for the primary
  calendar only, retaining all history returned by the server and all future
  events, including reminders.
- Standard periodic background synchronization and a system notification for
  a persistent user-actionable synchronization problem.
- Server TLS trust combines app-bundled private root/issuing CA certificates
  with the Android system trust store so a server can also use Let's Encrypt.
  Private CA certificates must never be added to the system trust store, and
  seamless server-certificate rollover is not required.
- Android 16 is the only supported platform. Distribution is direct local
  installation, not Google Play.

The current implemented boundary is connection configuration and verification:
the app validates one profile, uses Android KeyChain for client-certificate
selection, checks HTTPS/mTLS and ActiveSync `OPTIONS`, and persists settings only
after success. Calendar Provider writes, WorkManager scheduling, reminders, and
notifications still require later OpenSpec changes.

## Module structure and ownership

The permitted dependency direction is:

```text
:app -> :feature:settings -> :core
     -> :infrastructure  -> :core
```

- `:app` owns the application manifest, launcher activity, app resources, and
  manual dependency composition. Keep reusable logic out of this module.
- `:core` is pure Kotlin/JVM and owns platform-independent connection models,
  validation, failure types, use cases, and adapter contracts. It must not
  depend on Android and uses explicit public API mode.
- `:feature:settings` owns settings presentation state, ViewModel, and Compose
  UI. It may depend on `:core` but must never depend on `:infrastructure`.
- `:infrastructure` owns Android KeyChain access, DataStore persistence,
  system-plus-local TLS trust, and the ActiveSync HTTP probe. It depends only on
  `:core`.

Do not split speculative Exchange, certificate, calendar, persistence, or sync
modules before an active OpenSpec change identifies stable responsibilities.
Do not add a dependency-injection framework for the current manual composition
root.

## Toolchain and local environment

The supported, pinned build matrix is JDK 21, Java/Kotlin bytecode target 17,
Gradle Wrapper 9.5.1, Android Gradle Plugin 9.1.1, Kotlin/Compose plugin 2.4.10,
and Android API 36 for `minSdk`, `targetSdk`, and `compileSdk`. Dependency
versions belong in `gradle/libs.versions.toml`; dynamic versions and
project-local repositories are forbidden.

Local prerequisites:

- JDK 21 available to Gradle through `JAVA_HOME` or the active shell.
- Android SDK Platform 36 and Build Tools 36.0.0.
- An Android 16 device connected through `adb` only when installing manually.
- Network access on the first build to resolve the pinned wrapper and
  dependencies. A global Gradle installation is not required or supported.

Optional private-CA trust anchors for a local build belong only in
`infrastructure/src/main/assets/tls/`. The directory is ignored in full and may
be absent. The loader accepts X.509 certificates encoded as PEM or DER; never
place private keys or client certificates there. Without local anchors the app
still builds and retains Android system trust, while private-CA validation
reports a user-actionable configuration error.

Create an untracked `local.properties` at the repository root when the Android
SDK is not otherwise discoverable:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Never commit `local.properties` or replace it with a repository-bundled SDK.

## Build and verification commands

Use the checked-in wrapper for every Gradle operation:

- `./gradlew :app:assembleDebug` builds the installable debug APK.
- `./gradlew test` runs every JVM/Android-local unit test.
- `./gradlew lintDebug` runs Android Lint for all Android modules.
- `./gradlew verifyBootstrap` compiles production and unit-test sources, runs
  every local unit test and Android Lint task, and assembles the debug APK.
- `./gradlew :app:installDebug` installs the debug APK on a connected Android
  16 device; launching and inspecting the shell remains a manual check.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Kotlin and
Java compilation are the type checks. Kotlin/Java compiler warnings and Android
Lint warnings are errors except for narrowly documented toolchain-version and
adaptive-icon detector conflicts; there is no lint baseline, Detekt, or Ktlint.

## Test policy and repository hygiene

Automated tests in this stage are local JVM unit tests under `src/test` only.
Do not add `src/androidTest`, instrumentation runners, emulator/connected test
tasks, Robolectric, integration tests, or end-to-end tests. Every observable
behavior and bug fix must follow test-first RED-GREEN-REFACTOR and include a
unit regression test where the unit-only boundary can exercise the behavior.
The server-backed manual verification checklist has been completed on a Xiaomi
17 running Android 16 against a real Exchange Server. It covered HTTPS/mTLS and
ActiveSync, the real Calendar Provider, and actual execution of the 15-minute
WorkManager periodic work. This does not expand the unit-only automated test
boundary described above.

Generated build outputs, Gradle/Kotlin/IDE state, APKs, SDK paths, debug
keystores, production signing material, credentials, private keys, client
certificates, private server endpoints, and locally supplied public CA trust
anchors must not be tracked. Local public CA anchors are packaged from the
ignored asset directory at build time. Ignore rules are only a guardrail: do not
create secrets inside the repository, and inspect the tracked diff before
completion.

## Sources of truth

- `openspec/specs/` describes the currently accepted system behavior.
- `openspec/changes/<change-name>/` describes an in-progress change.
- `docs/` explains the currently implemented architecture and capabilities for
  developers. It is not normative and must agree with `openspec/specs/`.
- Archived OpenSpec changes preserve historical decisions and must not be
  treated as current-state documentation.
- For OpenSpec-managed changes, its `proposal.md`, `specs/`, `design.md`,
  and `tasks.md` are the canonical planning artifacts.
- Do not create a parallel implementation plan or design document when the
  corresponding OpenSpec artifacts already exist.
- When implementation discoveries change the intended behavior or design,
  update the OpenSpec artifacts before continuing.

## OpenSpec and Superpowers integration

- Use OpenSpec for requirements, scenarios, design decisions, and task tracking.
- Use Superpowers for TDD, systematic debugging, code review, and verification.
- Do not use git worktrees. Ignore superpowers:using-git-worktrees.
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
4. Implement using `$test-driven-development`.
5. Use `$systematic-debugging` for unexpected behavior instead of speculative fixes.
6. Run all relevant tests, linting, type checks, and builds.
7. Validate the implementation with `$openspec-verify-change`.
8. Run Codex `/review` against the resulting diff.
9. Immediately before archiving, update every affected file under `docs/` to
   match the final implementation and accepted main specs, remove stale
   statements, and verify that planned behavior is not presented as implemented.
   Do not archive while affected developer documentation is knowingly stale.
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
- Affected developer documentation reflects the post-change implemented state
  and contains no stale or prematurely documented behavior; configuration is
  updated when needed
- No secrets or generated local files are included
