## 1. Root build and local environment

- [ ] 1.1 Create the Gradle 9.5.1 wrapper with its distribution checksum, root Kotlin DSL settings/build files, centralized Google/Maven Central/plugin repositories, and `gradle/libs.versions.toml` pinned to the design matrix; verify `./gradlew --version` and `./gradlew projects` run on JDK 21 without a global Gradle installation.
- [ ] 1.2 Configure Android API 36, Java toolchain 21 with bytecode target 17, AGP built-in Kotlin for Android modules, Kotlin 2.4.10 for JVM/Compose plugins, deterministic `gradle.properties`, and no dynamic dependency versions; resolve any toolchain incompatibility by updating `design.md` before changing the matrix.
- [ ] 1.3 Add `.gitignore` and `.editorconfig` rules for machine-local SDK files, Gradle/Kotlin/IDE state, build outputs, APKs, keystores, keys, and certificates; verify a local Gradle sync does not add generated or sensitive files to `git status`.

## 2. Module graph

- [ ] 2.1 Add the pure Kotlin/JVM `:core` module with explicit public API mode and JUnit Platform configuration; add a minimal failing unit test for the unconfigured bootstrap state before adding the production state needed to make it pass.
- [ ] 2.2 Add the Android library `:infrastructure` depending only on `:core`, with its namespace, empty manifest, unit-test/lint configuration, and no product classes or integration dependencies; verify the module compiles independently.
- [ ] 2.3 Add the Android library `:feature:settings` depending only on `:core`, enable Compose through the Kotlin Compose plugin and stable BOM, and configure JUnit Platform local tests; verify Gradle's dependency graph contains no feature-to-infrastructure edge.
- [ ] 2.4 Add the `:app` Android application module with application ID `net.mixalich7b.exchangesync`, Android 16 min/target/compile SDK values, debug packaging, theme/resources, and dependencies on `:feature:settings` and `:infrastructure`; verify `:app:assembleDebug` produces an installable APK without release-signing configuration.

## 3. Minimal vertical slice

- [ ] 3.1 Add a failing JVM unit test in `:feature:settings` describing how the `:core` unconfigured state maps to the immutable settings UI state, including application identity and the not-configured message.
- [ ] 3.2 Implement the smallest pure state-to-presentation mapping that satisfies the unit test, without editable connection fields, persistence, permissions, network calls, calendar access, background work, or notifications; run all JVM/local unit tests.
- [ ] 3.3 Implement the single Compose settings shell and launcher activity that render the tested UI state through manual composition; verify the bootstrap manifest requests no calendar, account, certificate, network-state, background-work, or notification permission.

## 4. Static and aggregate verification

- [ ] 4.1 Configure Kotlin/Java compilation warnings and Android Lint according to `design.md`, with warnings-as-errors where supported, no lint baseline, and no Detekt/Ktlint dependency; run module compilation and `lintDebug` successfully.
- [ ] 4.2 Register the root `verifyBootstrap` lifecycle task with explicit dependencies on compilation, every JVM/local unit test, Android Lint, and debug APK assembly, and confirm that it has no connected-device or instrumentation-test dependency.
- [ ] 4.3 Run `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew verifyBootstrap` from a clean checkout-equivalent state and record/fix all failures without adding instrumentation, emulator, integration, or end-to-end tests.

## 5. Repository documentation

- [ ] 5.1 Expand `AGENTS.md` while preserving its existing OpenSpec/Superpowers rules, adding the product summary, explored first-stage constraints, bootstrap non-goals, module tree and dependency direction, supported toolchain, and ownership guidance.
- [ ] 5.2 Document in `AGENTS.md` the JDK/Android SDK prerequisites, local `local.properties` setup, exact build/unit-test/lint/aggregate/install commands, debug APK location, unit-only test policy, and secret/generated-file rules; verify every documented command matches an existing Gradle task.

## 6. Final bootstrap validation

- [ ] 6.1 Inspect the final tracked diff for unrelated files, generated outputs, server endpoints, credentials, private keys, certificate material, signing secrets, instrumentation-test sources, and unapproved production dependencies; remove or correct every finding.
- [ ] 6.2 Validate the `bootstrap-project` OpenSpec change strictly and confirm implementation behavior matches every `project-bootstrap` scenario, documenting the requested manual-only limitation for launch/install behavior.
- [ ] 6.3 When an Android 16 device is available, run `./gradlew :app:installDebug` and manually confirm the launcher opens the side-effect-free not-configured settings shell; do not add a connected or instrumentation test to automate this check.
