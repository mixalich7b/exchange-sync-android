## Context

The bootstrap project currently exposes only a static settings screen. The first
product-facing change must let a user configure the single Exchange connection,
select an Android-managed client certificate, and prove that the server is an
ActiveSync endpoint before replacing the saved profile.

The server is always addressed through HTTPS on port 443 and may present either
a certificate issued by the Android system trust store (for example, Let's
Encrypt) or a certificate issued by the private CA supplied locally with this
project. The private CA files must not be committed or installed into the Android
system trust store. A clean checkout without those files must remain buildable
and usable with publicly trusted servers.

This change crosses all existing modules and introduces security-sensitive TLS,
Android KeyChain, persistence, and HTTP integration. Automated coverage remains
limited to local JVM unit tests; the thin Android and network adapters are
covered by compilation, lint, and manual device checks.

## Goals / Non-Goals

### Goals

- Provide one editable connection form for email, `domain\login`, server
  hostname, and an Android KeyChain client-certificate alias.
- Validate fields locally and show all relevant field errors before any network
  operation.
- Use the Android system certificate chooser for VPN and application
  certificates and retain only the selected alias.
- Validate HTTPS, server trust, hostname, mTLS client-certificate use, and the
  ActiveSync `OPTIONS` response before atomically replacing the saved profile.
- Trust both Android system roots and locally packaged private-CA roots and
  intermediates without changing the device trust store.
- Preserve the attempted draft and the previously saved profile when validation
  or connection checking fails, while presenting a stable, actionable error.
- Keep clean-checkout builds reproducible when local private-CA files are absent.
- Cover observable domain and presentation behavior with JVM unit tests and run
  lint and build verification.

### Non-Goals

- Calendar discovery, reading, persistence, device-calendar writes, reminders,
  background synchronization, or sync notifications.
- ActiveSync commands beyond the capability `OPTIONS` request.
- Password authentication, configurable ports, plain HTTP, alternate endpoint
  paths, multiple profiles, or Android's built-in Exchange account integration.
- Importing, exporting, or copying client private keys or certificates.
- Certificate pinning, automatic server-certificate rollover, or modifying the
  Android system trust store.
- Adding a third-party cryptographic provider solely for unsupported private-CA
  algorithms.
- Automated instrumentation, end-to-end, or live-server tests in this change.

## Decisions

### 1. Preserve the existing module direction and compose dependencies in `app`

Responsibilities are split as follows:

- `core` owns immutable connection draft/profile models, deterministic
  validation, connection failure categories, repository and verifier contracts,
  and the save-connection orchestration use case. It remains free of Android and
  HTTP implementation details.
- `feature:settings` owns the settings state, events, ViewModel, and Compose UI.
  It depends only on `core` contracts.
- `infrastructure` owns Preferences DataStore persistence, KeyChain credential
  resolution, local-CA asset loading, trust-manager composition, and the OkHttp
  ActiveSync probe.
- `app` owns the Activity-level Android certificate chooser, manual dependency
  composition, lifecycle wiring, and the `INTERNET` permission.

The save flow is:

1. The UI submits its current draft.
2. The core use case validates every field without network access.
3. Infrastructure resolves the selected KeyChain alias to its private key and
   certificate chain.
4. Infrastructure builds the system-plus-local TLS context and sends the probe.
5. Core persists the profile in one operation only after the probe succeeds.
6. The UI reports success or preserves the draft and maps a typed failure to an
   actionable message.

This keeps policy testable on the JVM and limits Android framework use to thin
adapters. Moving orchestration into the Activity would reduce initial files but
would couple behavior to lifecycle callbacks and make atomic-save failure cases
harder to test.

### 2. Treat saving as a validate-probe-commit transaction

The save use case performs local validation, credential resolution, TLS/HTTP
probing, and finally one repository write. It never writes an unverified draft.
An in-progress save disables duplicate submissions but leaves the form visible.

On failure, the attempted field values and certificate selection remain in the
UI, the previous persisted profile remains unchanged, and a typed error is
shown. Merely editing fields or selecting a certificate never triggers a probe.

Persisting first and rolling back was rejected because rollback can itself fail,
briefly exposes unusable configuration to later workers, and complicates the
single-profile invariant.

### 3. Use Android KeyChain as the sole client-key authority

`MainActivity` launches `KeyChain.choosePrivateKeyAlias` for an HTTPS URI using
the entered host and port 443. The current alias is supplied as the preselected
value. No issuer or key-type filter is imposed because private deployments may
use different acceptable client issuers and algorithms. Cancelling the chooser
preserves the existing selection; a newly returned alias replaces it.

The application stores only the opaque alias. For a probe, the infrastructure
adapter calls `KeyChain.getPrivateKey` and `KeyChain.getCertificateChain`, then
exposes that exact key and chain through a fixed-alias `X509KeyManager`. The
completed TLS handshake must report a local certificate whose leaf matches the
selected chain; otherwise the result is a client-certificate failure.

The private key is never serialized, logged, copied, or returned to presentation
code. Direct PKCS#12 import was rejected because the requirement is to select an
already installed VPN/application certificate and leave key custody to Android.

### 4. Combine system and optional local CA trust without pinning

The TLS adapter creates the platform default `X509TrustManager` and a second
trust manager backed by X.509 certificates loaded from
`infrastructure/src/main/assets/tls/`. A composite manager accepts a server chain
when either the system manager or the local manager validates it. Normal Android
hostname verification remains enabled. There is no trust-all fallback, custom
hostname verifier, or leaf-certificate pinning.

The asset loader discovers files dynamically and accepts PEM or DER X.509
certificates. An absent directory is an empty local trust set, so a clean checkout
still builds and publicly trusted endpoints continue to work. Malformed or
unsupported local files are recorded and reported when local trust is needed;
they do not prevent a connection already accepted by the system trust manager.

When both trust managers reject the server chain, the failure classifier uses
structured certificate-path causes rather than exception-message text. When the
system failure contains a `CertPathValidatorException` whose reason is
`PKIXReason.NO_TRUST_ANCHOR`, the local trust status takes priority: missing or
invalid assets produce the corresponding local-CA category, while an available
local trust set produces a server-trust category. A more specific rejection from
the local validator does not override a missing or invalid local-CA diagnostic;
this intentionally directs the user to repair the packaged CA material first.
When system validation does not identify a missing trust anchor, expired,
not-yet-valid, revoked, malformed, signature, and ambiguous certificate failures
remain server-trust failures. Hostname verification remains its separate
category.

The five current files under `tls_certs_tmp/` are moved locally into the asset
directory during implementation. `/infrastructure/src/main/assets/tls/` is
explicitly ignored, and developer documentation describes this local setup. No
source or generated-resource reference assumes the directory exists.

Using Android Network Security Configuration alone was rejected because it
cannot make an ignored, optionally absent local asset set available while also
providing the failure classification required by the UI. Pinning was rejected
because the endpoint legitimately alternates between private-CA and public-CA
chains.

### 5. Probe the exact ActiveSync endpoint with a strict `OPTIONS` request

The infrastructure verifier uses OkHttp to start with an `OPTIONS` request to
`https://<hostname>:443/Microsoft-Server-ActiveSync`. OkHttp's automatic redirect
handling is disabled at the client level so the verifier can follow redirects
explicitly without allowing the library to rewrite `OPTIONS` to `GET`. For each
redirect response, the verifier resolves `Location` against the current URI,
requires HTTPS, reissues `OPTIONS`, and records the visited URI. Redirects to a
different hostname are allowed, but every destination must pass normal hostname
and certificate-chain validation. A chain fails if `Location` is absent or
malformed, it downgrades to HTTP, revisits a URI, or exceeds five redirects.

The same TLS configuration and fixed-alias key manager are available at every
hop, so the selected client identity can participate in mTLS when requested by a
destination. The terminal successful handshake must report the selected leaf
certificate; an intermediate redirecting server is not required to request a
client certificate. KeyChain material resolution already uses an I/O dispatcher.
The verifier also constructs the transport through an injected background
dispatcher that defaults to `Dispatchers.IO`, keeping local-CA asset reads,
certificate parsing, trust-manager and SSL-context initialization, and OkHttp
client construction off the Android main dispatcher. Transport construction is
synchronous and intentionally has no hard deadline: the verifier does not pass
a timeout into certificate or TLS-context creation, and coroutine cancellation
cannot preempt a provider that is executing non-suspending setup. The verifier's
timeout clock starts before construction, so a setup that returns after the
deadline is mapped to timeout before probing; if setup returns earlier, the
remaining deadline covers the cancellable redirect/probe work. Consequently the
timeout is not a strict upper bound on the total Save duration. The client also
uses finite connection, read, and call timeouts of 10, 15, and 30 seconds
respectively for network operations.

A successful probe requires HTTP 200, a non-empty `MS-ASProtocolVersions` header
containing at least one supported version (`12.1`, `14.0`, `14.1`, `16.0`, or
`16.1`), and an `MS-ASProtocolCommands` header containing both `FolderSync` and
`Sync`. Header tokens are comma-separated, trimmed, and command matching is
case-insensitive. This establishes endpoint compatibility only; mailbox and
calendar access are explicitly deferred.

Unsafe, malformed, cyclic, or excessive redirects, authentication/authorization
failures, endpoint/method failures, server failures, DNS/connectivity errors, TLS
trust or hostname errors, client-certificate errors, and incompatible capability
headers map to distinct core failure categories. UI text is localized and never
displays stack traces, private-key details, or raw response bodies.

An ad-hoc URL connection was rejected because OkHttp provides maintained TLS,
timeouts, redirect policy, and predictable request/response behavior with a
small adapter surface.

### 6. Persist one profile atomically with Preferences DataStore

Preferences DataStore stores exactly one profile: email, `domain\login`, server
hostname, and client-certificate alias. One `edit` transaction replaces all
fields after a successful probe. Loading a missing profile initializes the empty
form; loading an existing profile prepopulates it.

No password, private key, certificate bytes, capability response, or connection
error is persisted. The existing application backup policy remains disabled.
Encryption is not introduced because this change stores no authentication
secret and Android retains custody of the key; adding passwords or other secrets
would require a separate storage design.

SharedPreferences was considered but DataStore provides serialized, coroutine-
friendly atomic updates and explicit read failures while fitting the existing
Kotlin architecture.

### 7. Keep UI state lifecycle-aware and errors deterministic

The settings ViewModel exposes immutable state containing field values, field
errors, selected alias display, saved/connected status, initial-loading progress,
save progress, and an optional typed connection error. Initial loading starts in
a blocking state. Until the repository returns a profile, no profile, or an
error, the screen disables every text field, certificate-selection action, and
Save action, and the ViewModel ignores the corresponding events. The terminal
load result is then applied atomically and clears the loading state, so a late
repository response cannot overwrite an active draft or reset an in-progress
save. After loading, the ViewModel serializes save attempts normally. Activity
result handling feeds the selected alias back into the ViewModel without placing
Android KeyChain APIs in the feature module.

Validation rules and error-category-to-message mapping are deterministic. The
screen does not show raw exception messages. Compose receives callbacks and
state only, which permits state/reducer and ViewModel behavior to be unit tested
with fakes.

### 8. Pin compatible stable dependencies and keep tests local

Implementation will add stable versions through the version catalog: OkHttp 5.x
for HTTPS, AndroidX DataStore Preferences 1.2.x for the single profile, Kotlin
coroutines 1.11.x for asynchronous boundaries, and a lifecycle/ViewModel version
compatible with the project's pinned AGP and compile SDK (2.10.x rather than a
release requiring AGP 9.2/compile SDK 37). Exact patch versions are resolved and
pinned during implementation, then verified by the build.

JVM unit tests drive validators, capability parsing, failure classification,
save ordering and atomicity, settings state/ViewModel transitions, profile
encoding, and composite trust behavior using fakes. Android KeyChain, DataStore
framework construction, OkHttp socket behavior, and the real server are thin
integration boundaries verified by compilation, lint, and optional manual checks
on the Android 16 device. No Robolectric, instrumentation suite, MockWebServer,
or live-server test is added at this stage.

## Risks / Trade-offs

- **Ignored CA assets make private trust machine-local.** A clean checkout cannot
  validate the private chain until a developer installs the files in the
  documented asset directory. System-trusted servers remain usable, and the app
  reports missing local trust material instead of failing the build.
- **Some supplied certificates use algorithms not supported by the device
  provider.** This change relies on Android's available X.509 providers and does
  not ship a new cryptographic provider. Unsupported parsing or validation is
  reported as a local-CA/TLS configuration error.
- **TLS failures can be ambiguous.** Certificate expiry, hostname mismatch,
  unknown issuer, and client rejection may surface through nested SSL exceptions.
  The classifier uses a structured system `NO_TRUST_ANCHOR` reason when available
  and then intentionally prioritizes missing or invalid local-CA material over a
  more specific local-validator rejection. When that structured system reason is
  unavailable, it falls back to the general server-trust category instead of
  guessing from provider-specific messages.
- **TLS transport construction is not strictly time-bounded.** It runs away from
  the Android main dispatcher, but certificate parsing and security-provider
  initialization are synchronous and receive no propagated deadline. A provider
  that blocks can keep Save in progress beyond the nominal probe timeout; this
  trade-off is accepted to avoid additional executor/process isolation for local
  TLS setup.
- **A remembered KeyChain alias can later become unavailable.** Removal,
  revocation, or loss of user authorization is detected on the next save/probe
  and prompts the user to select an available certificate again.
- **`OPTIONS` does not prove calendar access.** It intentionally verifies only
  network/TLS/mTLS and ActiveSync capabilities. Mailbox authentication and
  calendar semantics remain risks for the later synchronization change.
- **A cross-host redirect broadens where the client certificate may be
  presented.** Each destination must be HTTPS and independently pass hostname
  and chain validation, the redirect chain is bounded, and the private key never
  leaves Android KeyChain. This accepts disclosure of the public client identity
  to a trusted redirected host in order to support legitimate Exchange routing.
- **Unit-only testing leaves platform integration risk.** Pure policy receives
  strong JVM coverage, but KeyChain-provider behavior, device TLS providers, and
  the private server require a later manual or instrumentation validation step.
- **Connection metadata is stored unencrypted.** It is app-private, contains no
  password or private key, and backup is disabled, but it still includes personal
  identifiers. Introducing authentication secrets would invalidate this trade-off.

## Migration Plan

1. Add the required dependency aliases and module dependencies, declare the
   `INTERNET` permission, create the documented ignored asset location, and move
   the five existing local CA files from `tls_certs_tmp/` without adding them to
   Git.
2. Add core models, validation, failure taxonomy, ports, and the save use case
   test-first.
3. Add infrastructure persistence, KeyChain, trust, and ActiveSync probe adapters
   behind those ports, with JVM tests for all extractable policy.
4. Replace the static settings state/screen with the form and lifecycle-aware
   presentation behavior, developed through unit tests.
5. Wire the Activity certificate chooser and manual dependency composition.
6. Verify clean-checkout behavior without local CA assets, then verify unit tests,
   lint, type/compile checks, debug assembly, and the OpenSpec scenarios. A manual
   Android 16 launch/certificate-chooser check may supplement but does not replace
   the required automated checks.

Rollback removes the connection wiring and restores the bootstrap screen. The
single local DataStore file may be cleared with application data; no server or
device trust-store state needs rollback. Locally ignored CA files can be moved
back or removed without Git history changes.
