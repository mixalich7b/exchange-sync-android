## ADDED Requirements

### Requirement: Exchange product-version 15.2 compatibility
The application SHALL support Exchange deployments in the product build family `15.2`, including Exchange Server 2019, when their ActiveSync endpoint advertises at least one mutually supported ActiveSync protocol version from 14.0, 14.1, 16.0, or 16.1 and the required commands. It SHALL negotiate only values advertised in `MS-ASProtocolVersions` and SHALL NOT interpret the Exchange product build number `15.2` as an ActiveSync wire-protocol version.

#### Scenario: Exchange Server 2019 advertises ActiveSync 16.1
- **WHEN** an Exchange Server product build in the `15.2` family returns a compatible `OPTIONS` response advertising ActiveSync 16.1 with `FolderSync` and `Sync`
- **THEN** the connection check succeeds using ActiveSync 16.1 without requiring or sending an ActiveSync version named 15.2

#### Scenario: Exchange 15.2 advertises an older supported protocol
- **WHEN** an Exchange Server product build in the `15.2` family advertises ActiveSync 14.1 but no supported 16.x protocol version
- **THEN** the connection check succeeds using ActiveSync 14.1

#### Scenario: Product-version metadata is absent
- **WHEN** a compatible endpoint advertises a supported ActiveSync protocol and required commands without exposing an Exchange product build number
- **THEN** absence of product-version metadata does not prevent connection verification

#### Scenario: 15.2 appears only as a protocol value
- **WHEN** `MS-ASProtocolVersions` contains 15.2 but none of 14.0, 14.1, 16.0, or 16.1
- **THEN** the application reports an ActiveSync compatibility error because 15.2 is not a supported ActiveSync wire-protocol version

### Requirement: Profile-bound connection cookie continuity
The application SHALL maintain a process-local HTTP cookie session for each exact connection profile identity and SHALL accept response cookies and send every unexpired cookie on later eligible HTTPS requests according to its secure, host/domain, and path attributes. The session SHALL be shared with calendar synchronization for the same profile, SHALL isolate cookies from other profile identities and ineligible redirect destinations, and SHALL NOT persist cookie names, values, or attributes in profile or synchronization storage.

#### Scenario: Connection response establishes a session
- **WHEN** a connection-check response sets a valid cookie and a later request for the exact same profile matches that cookie's secure, host/domain, and path scope
- **THEN** the later request sends the cookie without exposing its value in presentation state or persistent storage

#### Scenario: Redirect response sets a cookie
- **WHEN** an HTTPS redirect response sets a valid cookie and the next redirected `OPTIONS` request is eligible for it
- **THEN** the redirected request sends the cookie while continuing to apply the redirect and TLS validation policy

#### Scenario: Cookie does not match the next destination
- **WHEN** a cookie is expired, non-secure for an HTTPS-only flow, outside the next request's host/domain or path scope, or belongs to another profile identity
- **THEN** the application does not send that cookie with the request

#### Scenario: Exchange removes a cookie
- **WHEN** Exchange expires or replaces a previously accepted cookie
- **THEN** subsequent eligible requests use the updated cookie state and do not send the removed value

#### Scenario: Application process is recreated
- **WHEN** the application process ends and is later recreated with the same persisted profile
- **THEN** no prior cookie is restored from disk and a new process-local HTTP session begins empty

