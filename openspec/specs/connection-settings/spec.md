# connection-settings Specification

## Purpose

Defines how a user configures, validates, and safely persists the application's single authenticated connection to a private Exchange ActiveSync server.

## Requirements

### Requirement: Single connection profile form
The application SHALL provide one editable connection profile containing an email address, a `domain\login` user name, a server hostname, and a client-certificate selection. The port SHALL be fixed to 443 and the transport SHALL be HTTPS. Until the initial saved-profile lookup completes, the application SHALL show loading progress and prevent form editing, client-certificate selection, and Save.

#### Scenario: Saved-profile lookup is in progress
- **WHEN** the application has opened and the initial saved-profile lookup has not completed
- **THEN** it displays loading progress and prevents editing any profile value, opening the client-certificate selector, or starting Save

#### Scenario: No saved profile
- **WHEN** the initial saved-profile lookup completes without a profile
- **THEN** it displays an editable unconfigured form with all four required values available for entry or selection

#### Scenario: Saved profile is reopened
- **WHEN** the initial saved-profile lookup completes with a previously verified profile
- **THEN** it populates the form with that profile's email address, `domain\login`, server hostname, and remembered certificate selection

#### Scenario: More than one profile is requested
- **WHEN** the user successfully saves new connection values while a profile already exists
- **THEN** the application replaces that one profile rather than creating an additional profile

### Requirement: Deterministic field validation
The application SHALL validate all required values before starting a network request. The email address SHALL contain non-empty local and domain parts, `domain\login` SHALL contain non-empty domain and login parts separated by a backslash, the server value SHALL be a hostname without a scheme, path, query, fragment, or explicit port, and a client certificate SHALL be selected.

#### Scenario: One or more fields are invalid
- **WHEN** the user presses Save with a missing or malformed required value
- **THEN** the application identifies every invalid field, performs no connection attempt, and does not modify the saved profile

#### Scenario: Hostname includes connection syntax
- **WHEN** the server value includes `http://`, `https://`, a path, or an explicit port
- **THEN** the application rejects the server field instead of silently changing the fixed HTTPS port 443 endpoint

### Requirement: System client-certificate selection
The application SHALL delegate client-certificate choice to Android's installed VPN and app certificate selector and SHALL remember only the alias granted to the application.

#### Scenario: User selects a certificate
- **WHEN** Android returns a client-certificate alias selected by the user
- **THEN** the form displays a non-secret certificate identity and uses that alias for the next connection check

#### Scenario: User cancels certificate selection
- **WHEN** the Android certificate selector returns no alias
- **THEN** the application leaves the form's prior certificate selection unchanged and does not treat cancellation as a connection failure

#### Scenario: Remembered certificate is unavailable
- **WHEN** the selected alias no longer provides both a private key and certificate chain at Save time
- **THEN** the application asks the user to select a certificate again, performs no server request, and leaves the saved profile unchanged

### Requirement: Connection check precedes persistence
The application SHALL persist connection settings only after the complete HTTPS, mTLS, and ActiveSync capability check succeeds. Opening or editing the form SHALL NOT initiate a server request. Potentially blocking client-credential and TLS transport setup SHALL execute outside the Android main dispatcher.

#### Scenario: User edits the form
- **WHEN** the user changes any field without pressing Save
- **THEN** the application performs no connection check and does not modify the saved profile

#### Scenario: Connection check succeeds
- **WHEN** the user presses Save with valid values and the complete connection check succeeds
- **THEN** the application atomically persists the draft as the only profile and displays a successful connected status

#### Scenario: Connection check fails
- **WHEN** any validation, certificate, network, TLS, HTTP, or ActiveSync capability step fails
- **THEN** the application displays an actionable error, keeps the attempted draft visible for correction, and leaves the previously saved profile unchanged

#### Scenario: Save is already in progress
- **WHEN** a connection check is running
- **THEN** the application shows progress and prevents another simultaneous Save attempt

#### Scenario: Connection setup runs in the background
- **WHEN** a valid Save starts client-credential resolution and TLS transport construction
- **THEN** potentially blocking certificate, trust-manager, SSL-context, and HTTP-client setup executes outside the Android main dispatcher while Save progress remains visible

### Requirement: ActiveSync endpoint capability check
The connection check SHALL start by sending an HTTP `OPTIONS` request to `https://<hostname>:443/Microsoft-Server-ActiveSync`. It SHALL follow no more than five redirects, including redirects to another hostname, only when each destination uses HTTPS, while preserving the `OPTIONS` method and applying normal TLS hostname and chain validation at every destination. Success SHALL require the terminal response to have HTTP 200, both `MS-ASProtocolVersions` and `MS-ASProtocolCommands` response headers, at least one protocol version from 12.1, 14.0, 14.1, 16.0, or 16.1, and both `FolderSync` and `Sync` commands.

#### Scenario: Compatible ActiveSync response
- **WHEN** the endpoint returns HTTP 200 with the required headers, at least one supported version, and both required commands
- **THEN** the ActiveSync capability step succeeds

#### Scenario: Endpoint is not compatible
- **WHEN** the endpoint is missing, rejects `OPTIONS`, omits either capability header, offers no supported version, or omits `FolderSync` or `Sync`
- **THEN** the connection check fails with an ActiveSync endpoint or compatibility error and does not save the profile

#### Scenario: Server redirects the probe
- **WHEN** the configured endpoint returns a redirect with a valid HTTPS destination and the chain does not exceed five redirects
- **THEN** the application follows the redirect, repeats the `OPTIONS` request with the configured mTLS identity available, validates the destination hostname and certificate chain, and evaluates the terminal response for the required ActiveSync capabilities

#### Scenario: Redirect chain is unsafe or invalid
- **WHEN** a redirect has a missing or malformed destination, downgrades to HTTP, revisits an already requested URI, or exceeds five redirects
- **THEN** the connection check fails with an actionable redirect error and does not save the profile

### Requirement: Combined server trust and mandatory mTLS
The connection check SHALL validate the server hostname and certificate chain against the Android system trust store plus any valid private-CA certificates packaged locally with the application. It SHALL also verify that the selected client certificate was used during the TLS handshake. The application SHALL NOT add private CA certificates to the Android system store or disable normal hostname or chain validation.

#### Scenario: Server uses a system-trusted certificate
- **WHEN** the server certificate chains to an Android system trust anchor, including a public CA such as Let's Encrypt
- **THEN** TLS validation can succeed whether or not local private-CA files are packaged

#### Scenario: Server uses the packaged private CA
- **WHEN** the server certificate chains to a valid locally packaged private root or issuing CA and the hostname matches
- **THEN** TLS validation can succeed without installing that CA into Android's system trust store

#### Scenario: Local private-CA files are absent
- **WHEN** no local private-CA files were packaged and Android system validation specifically reports that the server chain has no trust anchor
- **THEN** the application remains usable with Android system trust and identifies the missing local CA material

#### Scenario: Server identity is invalid
- **WHEN** the server chain is untrusted, expired, malformed, or valid for a different hostname
- **THEN** the connection check fails without offering a trust-all bypass, reports a server-trust or hostname category rather than a local-CA category unless the structured failure identifies a missing trust anchor, and does not save the profile

#### Scenario: Client certificate is not used or is rejected
- **WHEN** the selected client certificate cannot participate in a successful mTLS handshake
- **THEN** the connection check fails with a client-certificate or mTLS error and does not save the profile

### Requirement: Actionable connection errors
The application SHALL map connection failures to stable user-facing categories without displaying raw stack traces or exposing certificate private-key material.

#### Scenario: Network failure
- **WHEN** DNS resolution, TCP connection, or a configured timeout fails
- **THEN** the application reports the corresponding server-not-found, connection, or timeout category

#### Scenario: TLS failure
- **WHEN** server trust, hostname verification, local CA parsing, client-key access, or mTLS negotiation fails
- **THEN** the application reports the most specific identifiable TLS or certificate category, using a missing or invalid local-CA category only when system certificate-path validation specifically identifies a missing trust anchor and otherwise using the server-trust category for server-certificate validation failures

#### Scenario: HTTP failure
- **WHEN** the endpoint returns 401, 403, 404, 405, a 5xx response, or an unsafe, malformed, cyclic, or excessive redirect chain
- **THEN** the application distinguishes authentication or access rejection, endpoint mismatch, redirect-policy failure, and server failure categories

#### Scenario: Protocol failure
- **WHEN** the HTTP response does not prove the required ActiveSync capabilities
- **THEN** the application reports an ActiveSync compatibility error rather than a generic network error

### Requirement: Connection credential boundaries
The application SHALL persist no password or client private key. It SHALL persist only the profile fields and the Android-granted client-certificate alias, and the connection check SHALL perform no calendar write, background scheduling, reminder creation, or notification setup.

#### Scenario: Profile is persisted
- **WHEN** a verified profile is saved
- **THEN** stored application data contains no password, exported private key, or copied client-certificate key material

#### Scenario: Connection check completes
- **WHEN** the connection check succeeds or fails
- **THEN** no calendar data is read or written and no synchronization work, reminder, or notification is scheduled

### Requirement: Manual saved-profile connection recheck
The application SHALL provide a user-initiated connection recheck for the loaded saved profile when the displayed form still matches that profile. The recheck SHALL apply the same field validation, client-certificate resolution, HTTPS and mTLS validation, redirect policy, ActiveSync `OPTIONS` capability requirements, timeout, and failure categories used by the check that precedes persistence, but it SHALL NOT write the profile or any verification result to persistent storage.

#### Scenario: Unchanged saved profile can be rechecked
- **WHEN** initial loading has completed with a saved profile and every displayed profile value matches the saved profile
- **THEN** the application permits the user to start a connection recheck for that profile

#### Scenario: No saved profile can be rechecked
- **WHEN** initial loading has not produced a saved profile
- **THEN** the application does not permit a connection recheck and continues to offer Save for creating the profile

#### Scenario: Unsaved edits cannot be rechecked as the saved profile
- **WHEN** any displayed profile value differs from the loaded saved profile
- **THEN** the application does not permit a connection recheck until the user either restores the saved values or successfully saves the changed profile

#### Scenario: Recheck succeeds
- **WHEN** the user starts a recheck and the complete connection check succeeds
- **THEN** the application reports a successful verified connection with current TLS certificate diagnostics and performs no persistent write

#### Scenario: Recheck fails
- **WHEN** validation, certificate resolution, network, TLS, HTTP, redirect, or ActiveSync capability verification fails during a recheck
- **THEN** the application reports the same actionable failure category used during Save, keeps the displayed and saved profile unchanged, and performs no persistent write

#### Scenario: Another connection operation is in progress
- **WHEN** Save or a recheck is already running
- **THEN** the application displays connection-check progress and prevents profile editing, another Save, recheck, or client-certificate selection until the operation completes

### Requirement: Successful server TLS certificate diagnostics
Every successful Save connection check and successful manual recheck SHALL expose a localized diagnostic summary for the server certificate chain validated for the terminal HTTPS response. The summary SHALL identify the terminal host and list the available X.509 certificates in leaf-to-issuer order with each certificate's subject, issuer, serial number, validity interval, and SHA-256 fingerprint. The application SHALL treat these diagnostics as ephemeral presentation data and SHALL NOT persist certificate encodings or diagnostic history.

#### Scenario: Save check returns a validated server chain
- **WHEN** a new or changed profile passes the complete connection check and is persisted
- **THEN** the application displays the successful connection state and diagnostics for the terminal TLS certificate chain used by that check

#### Scenario: Recheck follows an HTTPS redirect
- **WHEN** a manual recheck succeeds after one or more permitted HTTPS redirects
- **THEN** the diagnostics identify the terminal host and its validated certificate chain rather than a certificate chain from an earlier redirect response

#### Scenario: Certificate details are displayed safely
- **WHEN** successful TLS diagnostics are displayed
- **THEN** every available certificate is shown in leaf-to-issuer order with subject, issuer, serial number, validity interval, and SHA-256 fingerprint, without displaying PEM, DER, raw exception data, client private-key material, or a claim that an omitted trust anchor was supplied by the server

#### Scenario: Successful diagnostics are not persisted
- **WHEN** the application process is recreated and a saved profile is loaded without a new connection check
- **THEN** the saved profile remains available but no TLS certificate diagnostics are shown until another Save check or manual recheck succeeds

#### Scenario: Displayed diagnostics become stale
- **WHEN** the user changes any profile value or a new Save check or recheck fails
- **THEN** the application removes the previously displayed TLS certificate diagnostics instead of associating them with the changed or failed connection state

#### Scenario: A successful result lacks usable server certificates
- **WHEN** the terminal HTTPS result does not provide at least one usable X.509 server certificate for diagnostics
- **THEN** the application reports that server-certificate diagnostics are unavailable, does not report the connection check as successful, and exposes no partial certificate diagnostics
