## MODIFIED Requirements

### Requirement: Combined server trust and mandatory mTLS
The connection check SHALL validate the server hostname and certificate chain against the Android system trust store plus any valid private-CA certificates packaged locally with the application. It SHALL resolve the selected KeyChain private key and certificate chain and make that fixed identity the only client identity available to the TLS connection. A terminal validated HTTPS response that satisfies the ActiveSync capability check SHALL NOT be rejected solely because the Android TLS session exposes an empty or unavailable local-certificate list. The application SHALL NOT add private CA certificates to the Android system store, disable normal hostname or chain validation, or claim stronger client-certificate evidence than the platform exposes.

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
- **WHEN** the selected KeyChain private key or certificate chain cannot be resolved or configured, or server-requested client authentication cannot complete with that fixed identity
- **THEN** the connection check fails with a client-certificate or mTLS error and does not save the profile

#### Scenario: Successful TLS session omits local-certificate metadata
- **WHEN** the selected KeyChain material was resolved and configured as the only client identity, server and hostname validation succeed, and the terminal response satisfies the ActiveSync capability check but Android exposes no local certificates for the completed TLS session
- **THEN** the connection check succeeds and reports the client identity as configured with participation evidence unavailable instead of reporting that the selected certificate was rejected
