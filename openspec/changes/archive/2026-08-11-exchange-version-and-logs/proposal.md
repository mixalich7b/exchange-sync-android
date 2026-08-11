## Why

The application currently negotiates supported Exchange ActiveSync protocol versions, but it does not explicitly prove compatibility with the Exchange Server 2019 product-version family `15.2`, and its independently created HTTP clients discard Exchange response cookies. Connection and calendar failures are also reduced to stable user-facing categories without retaining enough on-device diagnostics to identify the underlying network, TLS/mTLS, protocol, event-validation, or synchronization cause.

## What Changes

- Define Exchange Server product version `15.2` compatibility through the server's advertised, mutually supported ActiveSync protocol version; `15.2` is not introduced as an ActiveSync wire-protocol value.
- Retain Exchange response cookies in a profile-scoped in-memory HTTP session and apply normal secure domain, path, expiry, and redirect rules when sending subsequent `OPTIONS`, `FolderSync`, and `Sync` requests during the application process lifetime.
- Add on-device Logcat diagnostics for connection verification, HTTP/network failures, TLS server validation, mTLS client authentication, redirect and capability negotiation failures, and calendar command failures.
- Add on-device Logcat diagnostics for malformed or unrepresentable calendar events and for synchronization lifecycle failures, retries, blocking outcomes, and unexpected exceptions, with correlation fields for the affected operation or synchronization run.
- Redact cookies, request and response bodies, authorization data, private keys, certificate encodings, account/email values, event content, and full query-bearing URLs from logs.
- Document how to collect and interpret the diagnostic tags with `adb logcat`.

Non-goals:

- Adding a fictitious ActiveSync protocol version `15.2`, enabling ActiveSync 12.1, or implementing Exchange provisioning-policy negotiation.
- Persisting cookies across process death or storing them in DataStore.
- Adding an in-app log viewer, log-file export, remote telemetry, analytics, or server-side logging.
- Logging raw HTTP/WBXML payloads, cookie values, credentials, personal calendar content, or cryptographic key material.
- Unit-testing Logcat emission; compatibility and cookie behavior remain covered by local unit tests.

## Capabilities

### New Capabilities

- `diagnostic-logging`: Safe, correlated, on-device Logcat diagnostics for connection, TLS/mTLS, ActiveSync, event-validation, Calendar Provider, worker, and synchronization failures.

### Modified Capabilities

- `connection-settings`: Clarify Exchange Server 2019/`15.2` compatibility and require response-cookie reuse during connection verification without treating `15.2` as an ActiveSync protocol version.
- `calendar-sync`: Require profile-scoped response-cookie reuse across capability discovery and calendar commands while keeping cookies process-local and secret.

## Impact

- `:infrastructure` ActiveSync transport construction, OkHttp cookie handling, TLS/network exception boundaries, event parsing and Calendar Provider adapters, and WorkManager adapters.
- `:core` synchronization diagnostics boundary and run correlation, while retaining the module's pure Kotlin/JVM and Android-free contract.
- `:app` manual composition of the shared process-local HTTP session and Android Logcat diagnostics adapter.
- Local unit fixtures for Exchange Server 2019-style capability negotiation and cookie reuse; existing connection, calendar, lint, and assembly verification.
- Developer documentation for compatibility, cookie lifetime, redaction, diagnostic tags, and ADB collection commands.
