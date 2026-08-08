## ADDED Requirements

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
