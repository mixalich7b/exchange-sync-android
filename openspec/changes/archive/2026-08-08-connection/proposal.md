## Why

The bootstrap application cannot yet collect or validate the information needed to connect to the private Exchange server. This change adds a single persistent connection profile and proves that the configured HTTPS, mTLS, trust, and ActiveSync endpoint work before replacing any previously valid settings.

## What Changes

- Replace the static not-configured shell with an editable settings screen for email address, `domain\login`, server hostname, and an Android KeyChain client-certificate alias.
- Let the user choose the client certificate through Android's installed VPN and app certificate picker; never import, copy, or persist its private key.
- On Save, validate the form and selected alias, connect to `https://<hostname>:443/Microsoft-Server-ActiveSync` with mTLS, follow a bounded chain of HTTPS redirects while preserving `OPTIONS` and validating every destination, and require a successful terminal ActiveSync response with protocol capability headers.
- Persist the single profile only after the connection check succeeds. On failure, show an actionable error, keep the draft visible, and leave the previously saved profile unchanged.
- Trust both the Android system trust store and optional app-local private-CA certificates. Move the currently local CA files from `tls_certs_tmp` into an Android asset location during implementation and keep that location ignored by Git.
- Keep clean-checkout builds valid when local CA assets are absent; such builds retain system trust and report a specific trust/configuration error when a private-CA server cannot be validated.
- Add only JVM/local unit tests; do not add instrumentation, emulator, integration, or end-to-end tests.

## Capabilities

### New Capabilities

- `connection-settings`: Defines the single connection profile, KeyChain certificate selection, atomic save-after-check behavior, combined TLS trust, ActiveSync `OPTIONS` verification, persistence, and user-visible errors.

### Modified Capabilities

- `project-bootstrap`: Removes the obsolete side-effect-free bootstrap-shell behavior and makes clean local builds explicitly independent of ignored private-CA asset files.

## Impact

- Changes the `:feature:settings` presentation state and Compose screen from static output to an interactive form with progress and error states.
- Adds platform-independent connection models, validation, save orchestration, and contracts to `:core`.
- Adds Android KeyChain, local profile persistence, optional private-CA loading, combined trust, and ActiveSync HTTP adapters to `:infrastructure`, composed manually by `:app`.
- Adds the `INTERNET` permission and current stable open-source dependencies for HTTP, persistence, and lifecycle-aware UI state where justified.
- Moves five ignored local certificate files out of `tls_certs_tmp`; no certificate, private key, credential, or server hostname is committed.

## Non-goals

- Synchronizing calendar folders or events, writing Calendar Provider data, scheduling background work, reminders, or notifications.
- Implementing ActiveSync commands beyond the HTTP `OPTIONS` capability probe or negotiating a synchronization policy.
- Supporting passwords, multiple profiles, configurable ports, HTTP, user-configurable endpoint paths, certificate import, or Android's built-in Exchange account integration.
- Automating server-certificate rollover or proving behavior against a real server in the unit-only automated test suite.
