## Context

See `proposal.md` for motivation and `specs/project-bootstrap/spec.md` for observable requirements. The repository currently contains only OpenSpec configuration and repository instructions. The local workstation has JDK 21, Kotlin 2.3.21, Android SDK platforms 35, 36.1, and 37.0, but no globally installed Gradle; all build entry points therefore need to go through the Gradle Wrapper.

The eventual product has Android-specific trust, KeyChain, Calendar Provider, and background-work integrations, but this change must establish boundaries without implementing or pretending to implement those integrations. Unit tests are the only automated test type in scope.

## Goals / Non-Goals

**Goals:**

- Produce a small, installable Android 16 project whose modules have intentional dependency direction.
- Pin a stable toolchain that is mutually supported rather than combining independently newest but unsupported releases.
- Prove the `app -> feature -> core` path with a side-effect-free settings shell and a JVM-testable state mapping.
- Reserve one coarse infrastructure boundary for later Android/server adapters without adding speculative product APIs or dependencies.
- Make build, unit test, lint, type checking, aggregate verification, and local installation discoverable and repeatable.

**Non-Goals:**

- Designing or implementing the ActiveSync protocol and future production-layer interfaces.
- Adding dependency injection, persistence, HTTP, XML/WBXML, certificate, calendar, WorkManager, or notification libraries.
- Creating a release-signing pipeline, CI service, emulator setup, or instrumentation-test harness.
- Optimizing module granularity for hypothetical future growth before real product code exists.

## Decisions

### 1. Use the latest mutually supported stable toolchain matrix

Pin the initial build to:

- JDK 21 for Gradle execution and Java toolchains, with Java/Kotlin bytecode target 17.
- Android Gradle Plugin 9.1.1.
- Gradle Wrapper 9.5.1.
- Kotlin 2.4.10 and Compose Compiler plugin 2.4.10.
- Android `minSdk`, `targetSdk`, and `compileSdk` 36.
- Stable Compose BOM 2026.06.00, Activity Compose 1.13.0, and JUnit Jupiter 6.0.3.

Kotlin 2.4.10 documents full support through AGP 9.1 and Gradle 9.5, so AGP 9.2.x and Gradle 9.6.x are deliberately not selected despite being newer individually. All versions are literal entries in `gradle/libs.versions.toml`; dynamic versions are forbidden. Android modules use AGP 9 built-in Kotlin support, while the pure Kotlin module uses the matching Kotlin JVM plugin. The Compose compiler is enabled with the Kotlin Compose plugin rather than a legacy compiler extension coordinate.

Alternative considered: choose the absolute latest AGP and Gradle. Rejected because that falls outside Kotlin's fully supported matrix and would make the bootstrap itself an interoperability experiment.

Alternative considered: remain on AGP 8.x. Rejected because the project is new, AGP 9 is stable, and no migration compatibility is required.

### 2. Start with four coarse modules

The Gradle project has this dependency shape:

```text
                       ┌──────────────────┐
                       │       :app       │
                       │ composition root │
                       └────────┬─────────┘
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
          ┌──────────────────┐    ┌──────────────────┐
          │ :feature:settings│    │ :infrastructure  │
          │ Compose + state  │    │ future adapters  │
          └────────┬─────────┘    └────────┬─────────┘
                   │                       │
                   └───────────┬───────────┘
                               ▼
                       ┌──────────────┐
                       │    :core     │
                       │ pure Kotlin  │
                       └──────────────┘
```

- `:app` is the Android application, manifest owner, launcher activity, theme host, and manual composition root. It contains no reusable domain or integration logic.
- `:core` is a Kotlin/JVM module for platform-independent state and, later, product models/contracts. It has no Android dependency and enables explicit public API mode.
- `:feature:settings` is an Android library containing the Compose settings shell and presentation mapping. It depends only on `:core` plus UI libraries.
- `:infrastructure` is an Android library reserved for later Exchange, KeyChain/TLS, Calendar Provider, persistence, and WorkManager adapters. During bootstrap it can contain only its manifest/module documentation and no placeholder product implementation.

`:feature:settings` never depends on `:infrastructure`; `:app` will eventually provide dependencies manually. No dependency-injection framework is justified for a single screen and no active integrations.

Alternative considered: one `:app` module. Rejected because it would not verify the architectural separation requested by the user and would make the first product change responsible for restructuring the build.

Alternative considered: separate modules for Exchange, certificates, calendar, persistence, and synchronization now. Rejected as premature; the coarse `:infrastructure` module can be split through a later OpenSpec change when real dependencies and ownership are known.

### 3. Make the vertical slice real but intentionally narrow

`:core` defines only the minimal configuration status needed by the shell, initially an unconfigured state. `:feature:settings` maps that state to immutable UI state and renders a single Compose screen identifying the application and reporting that it is not configured. `:app` supplies the initial state directly and hosts the screen.

There are no editable server fields, persistence, permissions, certificate chooser, network calls, calendar calls, background scheduling, or notifications. A JVM unit test covers the pure state-to-presentation mapping. The vertical slice therefore validates source compilation, cross-module visibility, Compose wiring, resources, and the unit-test engine without creating a disposable fake integration.

Alternative considered: an in-memory connection repository and editable form. Rejected because losing entered settings across process death would create misleading product behavior and an interface likely to change during the real settings proposal.

### 4. Keep build logic explicit and small

Use root `settings.gradle.kts`, root `build.gradle.kts`, module-local Kotlin DSL files, and a central version catalog. Repositories are declared centrally with project repositories rejected. Shared values are pinned centrally, while the small amount of Android module configuration is repeated rather than introducing a convention-plugin build before it pays for itself.

The Gradle Wrapper uses the binary distribution, records its distribution checksum, and is the only documented Gradle entry point. `gradle.properties` enables the build cache and configuration cache only after the complete verification command succeeds with them enabled. Project isolation remains disabled because it is not needed for four modules.

Alternative considered: a `build-logic` included build with convention plugins. Rejected for the initial four modules; it adds another Kotlin build and compatibility surface before meaningful build conventions emerge.

### 5. Treat compilation as type checking and Android Lint as static analysis

Kotlin compiler warnings are promoted to errors where the active compiler/plugin exposes a stable option. Java warnings use strict compiler flags where compatible with generated Android sources. Android modules configure Lint to abort on errors, treat warnings as errors, check release-relevant issues for debug sources, and use no baseline initially. `:core` enables explicit API mode so cross-module contracts cannot become public accidentally.

Do not add Detekt, Ktlint, or a formatter plugin in this change. Android Lint plus the Kotlin/Java compilers satisfy the requested lint and type-checking boundary without another plugin compatibility axis. `.editorconfig` captures basic whitespace and Kotlin style.

### 6. Use JUnit Platform for JVM unit tests only

JUnit Jupiter 6.0.3 is the sole test framework. JVM and Android local unit-test tasks use JUnit Platform. The repository contains `src/test` tests only; it adds no `src/androidTest`, runner, Espresso, Compose UI test, Robolectric, emulator, or connected-test dependency.

Root documentation exposes these commands:

- `./gradlew :app:assembleDebug` — compile and package the debug APK.
- `./gradlew test` — run all JVM/local unit tests.
- `./gradlew lintDebug` — run Android Lint for debug sources.
- `./gradlew verifyBootstrap` — compile, run every unit test, run lint, and assemble the debug APK.
- `./gradlew :app:installDebug` — install on a connected Android 16 device.

`verifyBootstrap` is a small root lifecycle task with explicit dependencies on the relevant standard module tasks. It must not include any connected-device task.

### 7. Keep local deployment and sensitive material outside the repository

The application ID is `net.mixalich7b.exchangesync`, with a bootstrap version code/name suitable only for local development. The debug variant uses Android's normal machine-local debug signing key and produces an APK for `installDebug` or direct `adb install`. No publishing, app bundle, production keystore, or signing-property template is added.

`.gitignore` covers `local.properties`, Gradle/Kotlin/IDE caches, build outputs, native intermediates, APKs, keystores, and common certificate/private-key formats. The ignore rules are a guardrail, not permission to place secrets in the repository. The bootstrap contains no real hostname or certificate resource.

The only trust boundary exercised in this change is dependency acquisition from the Gradle Plugin Portal, Google Maven, Maven Central, and the Gradle Wrapper distribution endpoint. Project build scripts cannot add arbitrary repositories. Future server trust and client-key handling require a separate OpenSpec change.

### 8. Expand `AGENTS.md` as the development entry point

Preserve the existing OpenSpec/Superpowers workflow and add:

- a concise product summary and the explored first-product constraints;
- bootstrap non-goals and the rule that no Exchange behavior belongs in this change;
- the four-module tree and permitted dependency direction;
- toolchain and local prerequisites;
- exact wrapper commands listed above and the debug APK location;
- unit-only test policy, lint/type-check expectations, and the ban on tracked secrets/generated files;
- the requirement to update active OpenSpec artifacts before implementation when discoveries change scope or design.

No parallel architecture or task-plan document is created; the active OpenSpec change remains canonical.

## Risks / Trade-offs

- **[Toolchain versions move quickly]** → Pin the supported matrix above; upgrades are separate, reviewable dependency changes rather than dynamic resolution.
- **[AGP built-in Kotlin and the Kotlin JVM module could diverge]** → Use Kotlin 2.4.10 consistently, run `verifyBootstrap` with warning mode enabled, and revise this design before implementation if the supported matrix proves inaccurate.
- **[Warnings-as-errors can expose upstream/tooling warnings]** → Scope compiler flags to project sources and suppress only specific documented false positives; do not add a blanket lint baseline.
- **[Unit tests cannot prove Android launch/install behavior]** → Keep automated scope unit-only as requested, but verify the debug APK manually on the user's device without adding an instrumentation suite.
- **[A full future calendar architecture may outgrow `:infrastructure`]** → Split it only when a product OpenSpec change identifies stable responsibilities; dependency direction through `:core` remains the migration seam.
- **[Ignoring key and certificate extensions can hide accidentally created local files]** → Pair ignore rules with explicit review guidance that secrets must never be created inside the repository and inspect tracked files before completion.

## Migration Plan

1. Implement in an isolated branch/worktree because this is the first non-trivial project change.
2. Add the wrapper, root build metadata, version catalog, ignore/editor configuration, and verify Gradle can configure the empty module graph.
3. Add modules from the dependency leaves upward (`:core`, `:infrastructure`, `:feature:settings`, then `:app`), keeping the build green after each group.
4. Add the minimal state mapping, unit test, Compose shell, and launcher activity.
5. Enable strict compiler and lint settings, then run `verifyBootstrap` and `:app:assembleDebug` from a clean checkout-equivalent state.
6. Update `AGENTS.md`, confirm no generated or sensitive files are tracked, and manually install/launch the debug APK on Android 16 when a device is available.

Rollback is deletion of the newly added bootstrap files and restoration of the previous `AGENTS.md`; there is no persisted user data, server state, schema migration, or deployed production artifact to reverse.
