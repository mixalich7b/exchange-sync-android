## 1. OpenSpec and tests

- [x] 1.1 Add the calendar-sync delta, design, and task artifacts.
- [x] 1.2 Add failing unit tests for the new display name and scoped provider update.
- [x] 1.3 Add failing unit tests for startup migration success, retry, and idempotence.

## 2. Implementation

- [x] 2.1 Change new owned-calendar creation to use `Exchange-sync`.
- [x] 2.2 Implement a scoped Calendar Provider display-name update.
- [x] 2.3 Implement durable startup migration and wire it into the application composition root.

## 3. Documentation and verification

- [x] 3.1 Update current-state calendar documentation.
- [x] 3.2 Run relevant tests, lint, build, and `verifyBootstrap`.
- [x] 3.3 Verify the completed implementation against the OpenSpec change.
