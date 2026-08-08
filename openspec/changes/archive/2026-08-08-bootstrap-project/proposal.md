## Why

The repository has requirements and workflow guidance but no buildable Android project, so product work cannot begin with repeatable local verification. This change establishes a small, current, OpenSpec-managed foundation that can be built, tested, linted, and installed on the target Android 16 device before Exchange synchronization is implemented.

## What Changes

- Create a Kotlin DSL, Gradle Wrapper-based, multi-module Android project targeting Android 16 with a pinned, mutually supported stable toolchain.
- Establish `:app`, `:core`, `:infrastructure`, and `:feature:settings` module boundaries with one-way dependencies and no circular references.
- Add a minimal runnable vertical slice in which the application opens a settings shell backed by a small pure-Kotlin state model, sufficient to prove module wiring without implementing connection or synchronization behavior.
- Configure a version catalog, reproducible repositories, Java/Kotlin compiler settings, Android resources, and debug APK generation for local `adb` installation.
- Configure JVM unit tests only, Android Lint, Kotlin/Java compilation as type checking, warnings-as-errors where supported, and aggregate verification commands.
- Add local development hygiene and documentation, including ignored machine-local/generated files and an expanded `AGENTS.md` describing the product boundary, module structure, SDD/OpenSpec workflow, and build/test/lint commands.
- Explicitly exclude Google Play publishing, production signing automation, CI/CD, instrumentation tests, and all Exchange/calendar product integrations from this bootstrap.

## Capabilities

### New Capabilities

- `project-bootstrap`: Defines the buildable Android project, module boundaries, minimal launchable shell, local verification commands, and repository development contract.

### Modified Capabilities

None.

## Impact

- Adds the root Gradle build, wrapper, version catalog, Android modules, minimal source/resources, unit-test configuration, and local-development metadata.
- Establishes Android API 36 as the initial minimum, target, and compile platform and uses a JDK 21 local toolchain with a pinned compatible AGP/Gradle/Kotlin matrix.
- Adds build-time dependencies only where needed for the minimal Compose shell and unit-test framework; no network, persistence, WorkManager, certificate, or calendar libraries are introduced yet.
- Updates `AGENTS.md`; no accepted product specification under `openspec/specs/` is changed by this in-progress proposal.

## Non-goals

- Implementing ActiveSync, mTLS, certificate selection, TLS trust configuration, Calendar Provider access, background synchronization, notifications, or settings persistence.
- Connecting to any real Exchange server or embedding real certificates, credentials, signing keys, or other secrets.
- Supporting multiple profiles, Android versions below 16, Google Play delivery, app bundles, production release signing, or automated deployment.
- Adding instrumentation, emulator, end-to-end, or integration tests during this first stage.
