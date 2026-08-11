# project-bootstrap Specification

## Purpose

Defines the runnable, locally verifiable Android project foundation on which later OpenSpec-managed Exchange calendar capabilities can be implemented without introducing product behavior prematurely.
The current application has also completed manual server-backed verification on a Xiaomi 17 running Android 16 against a real Exchange Server; this status does not add connected-device or integration tests to the automated verification suite.

## Requirements

### Requirement: Reproducible local build
The project SHALL provide a checked-in build launcher and pinned dependency versions that produce an installable debug APK on a machine with JDK 21 and the Android 16 SDK, without requiring a separately installed Gradle executable or locally supplied private-CA files.

#### Scenario: Clean debug build
- **WHEN** a developer checks out the repository, provides the local Android SDK path, and runs the documented debug build command
- **THEN** the build completes using the checked-in launcher and produces a debug APK for the application

#### Scenario: Machine-local SDK configuration is absent
- **WHEN** the build is run without an Android SDK path available to Gradle
- **THEN** the build fails without modifying tracked project files or substituting a bundled SDK

#### Scenario: Machine-local private CA assets are absent
- **WHEN** the build runs without the ignored local private-CA asset directory
- **THEN** the build still produces the application and the resulting application retains Android system certificate trust

### Requirement: Android 16 application boundary
The application SHALL declare Android 16 as its minimum and target platform and SHALL be installable directly on an Android 16 device without any Google Play publishing step.

#### Scenario: Local device installation
- **WHEN** the generated debug APK is installed on an Android 16 device using the documented local installation command
- **THEN** Android accepts the package as a locally installed application and exposes its launcher activity

#### Scenario: Older Android device
- **WHEN** installation is attempted on a device below Android 16
- **THEN** Android rejects the package as incompatible

### Requirement: Unit-only automated verification
The project SHALL provide JVM unit-test tasks for production logic and SHALL exclude instrumentation, emulator, connected-device, end-to-end, and integration tests from the bootstrap verification suite.

#### Scenario: Unit tests pass
- **WHEN** the documented unit-test command is run and all unit assertions pass
- **THEN** the command exits successfully and produces local unit-test reports

#### Scenario: Unit test fails
- **WHEN** any unit assertion fails
- **THEN** the unit-test command and aggregate verification command exit unsuccessfully

#### Scenario: No device is connected
- **WHEN** the complete automated verification suite is run without an emulator or Android device
- **THEN** all configured verification tasks can complete without attempting a connected-device test

### Requirement: Static build verification
The project SHALL provide documented commands that compile all production and unit-test sources, run Android Lint, and fail the aggregate verification when a compiler, type, or configured lint error is present.

#### Scenario: Valid sources
- **WHEN** the aggregate verification command is run against valid sources
- **THEN** compilation, JVM unit tests, and Android Lint complete successfully

#### Scenario: Type error
- **WHEN** a production or unit-test source contains an unresolved or type-incompatible expression
- **THEN** aggregate verification fails during compilation

#### Scenario: Lint violation configured as fatal
- **WHEN** Android Lint reports a violation at a configured fatal severity
- **THEN** aggregate verification fails and identifies the affected source or resource

### Requirement: Safe local project configuration
The repository SHALL keep generated outputs, machine-local SDK configuration, caches, debug signing material, production signing material, credentials, private keys, client certificates, server certificates, and locally supplied private-CA assets out of tracked source files.

#### Scenario: Local build generates machine state
- **WHEN** a developer syncs, builds, tests, or installs the application locally
- **THEN** generated and machine-local files are ignored by version control

#### Scenario: Bootstrap is inspected for product secrets
- **WHEN** the tracked bootstrap files are reviewed
- **THEN** they contain no Exchange credentials, certificate material, signing secrets, or private server endpoints

#### Scenario: Private CA assets are installed locally
- **WHEN** private root or issuing CA files are placed in the documented Android asset location
- **THEN** version control ignores those files and the original temporary certificate directory is no longer used

### Requirement: Documented SDD development contract
The repository SHALL document its product boundary, module responsibilities, dependency direction, OpenSpec sources of truth, required SDD workflow, local prerequisites, and exact commands for build, unit tests, lint, aggregate verification, and device installation.

#### Scenario: Developer follows repository guidance
- **WHEN** a developer reads `AGENTS.md`
- **THEN** the developer can identify where new code belongs and which commands and OpenSpec workflow are required before claiming a change complete

#### Scenario: Product behavior is proposed after bootstrap
- **WHEN** a future change adds observable Exchange or calendar behavior
- **THEN** the documented workflow requires that behavior to be specified in OpenSpec before production code is modified
