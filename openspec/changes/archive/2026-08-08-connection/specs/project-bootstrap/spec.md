## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Minimal launchable vertical slice

**Reason**: The static, offline bootstrap shell is superseded by the real connection-settings capability, which intentionally selects a client certificate, performs a server probe on Save, and persists a verified profile.

**Migration**: Use the `connection-settings` requirements for launch, configuration, network, certificate, and persistence behavior; calendar synchronization remains outside this change.
