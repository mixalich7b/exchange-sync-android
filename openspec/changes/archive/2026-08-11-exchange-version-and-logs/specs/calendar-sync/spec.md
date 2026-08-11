## ADDED Requirements

### Requirement: ActiveSync calendar cookie continuity
Calendar synchronization SHALL use the exact saved profile's process-local HTTP cookie session across capability discovery, HTTPS redirects, `FolderSync`, priming `Sync`, paged `Sync`, retries, and continuation slices. When no live session exists after process recreation, synchronization SHALL establish a fresh capability session before issuing calendar commands even when persisted protocol checkpoints are otherwise reusable, so newly issued eligible cookies can accompany those commands.

#### Scenario: OPTIONS cookie is used by FolderSync
- **WHEN** capability discovery for the saved profile receives a valid cookie that is eligible for the following `FolderSync` request
- **THEN** `FolderSync` sends that cookie in the request

#### Scenario: Command cookie is used by later pages
- **WHEN** `FolderSync`, priming `Sync`, or a paged `Sync` response updates an eligible cookie
- **THEN** every later eligible ActiveSync command in the same process-local profile session uses the updated cookie state

#### Scenario: Redirected command updates the session
- **WHEN** a permitted HTTPS ActiveSync redirect response sets a cookie scoped to the redirect destination
- **THEN** eligible requests to that destination use the cookie and requests to unrelated destinations do not receive it

#### Scenario: Cold process resumes persisted checkpoints
- **WHEN** synchronization resumes persisted ActiveSync checkpoints after process recreation and no process-local cookie session is available
- **THEN** it performs fresh capability discovery before the first calendar command, retains any resulting cookies in the new session, and continues with the mutually supported protocol version

#### Scenario: Saved profile changes
- **WHEN** a different connection profile becomes active
- **THEN** calendar synchronization does not send cookies retained for the previous profile identity

