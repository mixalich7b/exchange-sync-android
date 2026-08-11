## Why

Real-device testing against a production Exchange endpoint exposed false-negative client-certificate verification, an ActiveSync 16.x recurrence parsing defect, missing visibility into an apparently empty initial mirror, an Android Calendar Provider cleanup failure, and synchronization controls that cannot recover from a blocked run. Together these defects can leave a verified profile with an empty or undeletable local calendar and no user path to retry.

## What Changes

- Stop treating `Handshake.localCertificates` as a portable proof that the fixed KeyChain identity participated in Android TLS. Keep the selected private key and certificate chain as the only configured client identity, require a successful validated HTTPS capability response, and report only the evidence the platform actually exposes.
- Parse ActiveSync 16.0/16.1 `airsyncbase:InstanceId` values as the protocol-defined Compact DateTime so recurring-event exceptions do not block an otherwise valid page.
- Preserve the full, unfiltered initial synchronization contract and add privacy-safe request/page/provider summaries that distinguish an empty Exchange response from parsing, planning, provider-application, and checkpoint failures.
- Keep exactly one application-owned calendar, identify whether it was created, reused, repaired, or deleted in diagnostics, and leave unrelated device-local calendars such as an OEM `account_name_local` calendar untouched.
- Make owned-calendar deletion compatible with Android 16 Calendar Provider while retaining the complete ownership predicate, and convert all provider-side runtime failures into durable actionable cleanup outcomes instead of uncaught retry loops or generic presentation-only errors.
- Allow an enabled blocked synchronization to be retried manually through the existing serialized run path, without discarding committed calendar data or checkpoints unless a full reset is already pending.
- Retain explicit redirect tracking and disabled OkHttp automatic redirects so ActiveSync methods/bodies, the five-hop HTTPS-only limit, cycle rejection, and per-hop diagnostics remain under application policy.
- Add unit regression coverage for every confirmed failure and update affected developer documentation after implementation.
- Non-goals: deleting or adopting unrelated local calendars, adding AccountManager/SyncAdapter integration, persisting payload diagnostics, logging personal calendar data, changing cooperative Cancel to delete the mirror, or adding a production dependency.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `connection-settings`: Make successful connection verification independent of unavailable Android local-certificate handshake metadata while retaining fixed-identity TLS and the strict manual redirect policy.
- `calendar-sync`: Accept protocol-correct ActiveSync 16.x recurrence instance identifiers, preserve complete initial mirroring, distinguish the single owned calendar from unrelated local calendars, and make owned-calendar cleanup reliable.
- `sync-scheduling`: Permit manual recovery from blocked enabled state and make disable/cleanup failures durable and retryable without uncaught worker loops.
- `diagnostic-logging`: Add safe protocol-page, ownership, provider-application, cleanup, and client-identity evidence needed to diagnose the observed device failures.

## Impact

The change affects connection and ActiveSync transport policy in `:infrastructure`, calendar value parsing and Calendar Provider ownership operations in `:infrastructure`, synchronization transitions in `:core`, settings control availability in `:feature:settings`, manual composition only if diagnostic fields require wiring, and the corresponding unit tests and `docs/` descriptions. It changes no public network endpoint, stored credential shape, module dependency direction, Android permission, or third-party dependency.
