## MODIFIED Requirements

### Requirement: Connection check precedes persistence
The application SHALL persist connection settings only after the complete HTTPS, mTLS, and ActiveSync capability check succeeds. Opening or editing the form SHALL NOT initiate a server request. Potentially blocking client-credential and TLS transport setup SHALL execute outside the Android main dispatcher. After a successfully verified new or changed profile is atomically persisted, the application SHALL activate a new synchronization generation that clears only the application-owned calendar and requests a full calendar synchronization; a failure in that post-persistence synchronization setup SHALL NOT roll back the verified profile.

#### Scenario: User edits the form
- **WHEN** the user changes any field without pressing Save
- **THEN** the application performs no connection check and does not modify the saved profile

#### Scenario: Connection check succeeds
- **WHEN** the user presses Save with valid new or changed values and the complete connection check succeeds
- **THEN** the application atomically persists the draft as the only profile, displays a successful connected status, invalidates the previous synchronization generation, clears only the application-owned calendar, and requests a full synchronization for the new generation

#### Scenario: Post-persistence synchronization setup fails
- **WHEN** a verified profile has been persisted but calendar access, cleanup, or background scheduling cannot be established
- **THEN** the saved profile remains the active profile and the application reports a separate actionable synchronization problem without restoring the previous profile

#### Scenario: Connection check fails
- **WHEN** any validation, certificate, network, TLS, HTTP, or ActiveSync capability step fails
- **THEN** the application displays an actionable error, keeps the attempted draft visible for correction, leaves the previously saved profile unchanged, and does not alter its calendar or synchronization generation

#### Scenario: Save is already in progress
- **WHEN** a connection check is running
- **THEN** the application shows progress and prevents another simultaneous Save attempt

#### Scenario: Connection setup runs in the background
- **WHEN** a valid Save starts client-credential resolution and TLS transport construction
- **THEN** potentially blocking certificate, trust-manager, SSL-context, and HTTP-client setup executes outside the Android main dispatcher while Save progress remains visible

### Requirement: ActiveSync endpoint capability check
The connection check SHALL start by sending an HTTP `OPTIONS` request to `https://<hostname>:443/Microsoft-Server-ActiveSync`. It SHALL follow no more than five redirects, including redirects to another hostname, only when each destination uses HTTPS, while preserving the `OPTIONS` method and applying normal TLS hostname and chain validation at every destination. Success SHALL require the terminal response to have HTTP 200, both `MS-ASProtocolVersions` and `MS-ASProtocolCommands` response headers, at least one protocol version from 14.0, 14.1, 16.0, or 16.1, and both `FolderSync` and `Sync` commands.

#### Scenario: Compatible ActiveSync response
- **WHEN** the endpoint returns HTTP 200 with the required headers, at least one supported version from 14.0, 14.1, 16.0, or 16.1, and both required commands
- **THEN** the ActiveSync capability step succeeds

#### Scenario: Endpoint is not compatible
- **WHEN** the endpoint is missing, rejects `OPTIONS`, omits either capability header, offers no version from 14.0, 14.1, 16.0, or 16.1, or omits `FolderSync` or `Sync`
- **THEN** the connection check fails with an ActiveSync endpoint or compatibility error and does not save the profile

#### Scenario: Server redirects the probe
- **WHEN** the configured endpoint returns a redirect with a valid HTTPS destination and the chain does not exceed five redirects
- **THEN** the application follows the redirect, repeats the `OPTIONS` request with the configured mTLS identity available, validates the destination hostname and certificate chain, and evaluates the terminal response for the required ActiveSync capabilities

#### Scenario: Redirect chain is unsafe or invalid
- **WHEN** a redirect has a missing or malformed destination, downgrades to HTTP, revisits an already requested URI, or exceeds five redirects
- **THEN** the connection check fails with an actionable redirect error and does not save the profile

### Requirement: Connection credential boundaries
The application SHALL persist no password, client private key, client-certificate encoding, or exported key material. Credential persistence SHALL contain only the four profile fields and Android-granted client-certificate alias. The application MAY separately persist non-secret synchronization metadata needed to identify its device, serialize work, resume ActiveSync, and report state. A failed connection check or manual recheck SHALL perform no calendar write, background scheduling, reminder creation, or notification setup; only a successfully persisted new or changed profile can activate those post-persistence synchronization effects.

#### Scenario: Profile is persisted
- **WHEN** a verified profile is saved and synchronization state is initialized
- **THEN** stored application data can contain the profile, certificate alias, non-secret device identifier, enablement, generation, protocol keys, progress, and stable failure category but contains no password, exported private key, client-certificate bytes, raw server body, or event payload archive

#### Scenario: Connection check completes
- **WHEN** validation or the HTTPS, mTLS, and ActiveSync capability check fails before profile persistence, or an unchanged saved profile is only rechecked
- **THEN** no calendar data is read or written and no synchronization work, reminder, or synchronization-problem notification is scheduled as a side effect of that check

#### Scenario: Manual connection recheck completes
- **WHEN** a manual recheck succeeds or fails for an unchanged saved profile
- **THEN** it does not write the profile, change synchronization generation, clear calendar data, or schedule synchronization work
