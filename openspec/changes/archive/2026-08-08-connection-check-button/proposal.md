## Why

After the initial profile save, the user cannot manually repeat the same HTTPS, mTLS, and ActiveSync verification without initiating another save. A successful result also does not reveal which public server certificate chain the application actually received and validated, making TLS configuration and certificate-rollover problems harder to diagnose.

## What Changes

- Add a separate connection recheck action for an unchanged saved profile, using the same validation, HTTPS/mTLS, redirect, and ActiveSync `OPTIONS` rules as the check before persistence.
- Show recheck progress, prevent concurrent checks or saves, and present the same user-facing failure categories used while adding or changing a profile.
- Do not modify the saved profile during a recheck; when the form differs from the saved profile, the user must save those changes instead of checking them as the saved configuration.
- After a successful save or recheck, show a diagnostic summary of the public server certificate chain from the terminal TLS session, including readable identities, validity, and a SHA-256 fingerprint for each certificate in leaf-to-issuer order.
- Do not persist TLS diagnostics across process restarts, and clear stale diagnostics when the profile changes or a new attempt fails.
- Reuse the connection-result area for successful diagnostics and failures while keeping the localized presentation free of stack traces, PEM/DER encodings, and client private-key material.

### Non-goals

- Checking calendar synchronization or mailbox access, or creating background work.
- Persisting verification history, server certificates, or TLS diagnostics.
- Displaying client private-key material or complete certificate encodings, or adding any trust-validation bypass.
- Changing trust, redirect, timeout, or required ActiveSync capability policies.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `connection-settings`: adds manual re-verification of a saved profile and diagnostic presentation of the server TLS certificate chain after a successful check.

## Impact

- `:core`: a successful-check result carrying safe TLS diagnostics and a separate recheck use case with no persistence.
- `:infrastructure`: extraction and conversion of the peer certificate chain from the successful terminal OkHttp handshake.
- `:feature:settings`: recheck progress and availability state, result and TLS-chain presentation, and unit tests for state transitions and resources.
- `:app`: manual composition of the new core action; no new production dependency or saved-profile format change is required.
- `openspec/specs/connection-settings/` and `docs/connection.md`: normative behavior and post-implementation developer documentation.
