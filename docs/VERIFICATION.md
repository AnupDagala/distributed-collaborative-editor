# Verification record

Local verification: **2026-09-11**, Windows 11 x64. Results below are observed local results; the GitHub workflow has not been run in this task, and no deployment success is claimed.

## Environment

- Eclipse Temurin OpenJDK **21.0.12.1+1**, selected per process. System Java/global settings were not changed.
- Apache Maven **3.9.11**, dedicated local dependency cache.
- Node **24.11.0**. Frontend packages are locked in `frontend/package-lock.json`.
- Native PostgreSQL **17.11**, dedicated editor test and demo databases on loopback port 55432. The Compose/Testcontainers definitions use PostgreSQL 16; that image/version path is unverified locally.
- Redis **8.10.1**, loopback-only community Windows MSYS2 distribution from [redis-windows/redis-windows](https://github.com/redis-windows/redis-windows/releases/tag/8.10.1). Archive SHA-256: `4e8f2f956ed92feadf3f64b4e137ed34026438821e692e7ae22c9bba5976607a`, verified before extraction. This is not an official Windows Redis distribution. Compose uses the official Redis 8.2 image.
- Docker executable/daemon absent; Docker named pipe absent. A standalone Compose binary is available for static configuration validation only.

## Backend tests

`mvn clean verify` against dedicated native PostgreSQL/Redis: **BUILD SUCCESS**. Final suite: **12 Java unit tests + 17 native integration scenarios passed; 17 Docker-only scenarios skipped**. Container scenarios are the same inherited contract against Testcontainers. The workflow requires Docker and checks the XML report to prevent a skipped container suite being mistaken for a pass.

Unit coverage includes eight CRDT tests (40 seeded three-replica schedules with independently generated operations, 100 same-position delivery permutations, duplicates, delayed dependencies, concurrent deletes, Unicode, snapshot pending state, retryable capacity rejection, 10,000-element chain), two Redis rate-limit tests and two notification failure/no-echo tests.

Integration coverage includes real BCrypt/JWT/REST security; strict JSON/CORS; durable duplicate acknowledgment and conflicting reuse; atomic rollback; membership/revocation; single-use tickets and generation fencing; spoofed actors/sequences/clocks/expiry; snapshot-plus-suffix restoration and corruption detection; concurrent transactions; deletion; authenticated/origin-checked sockets; Unicode WebSocket broadcast/reconnect; binary/oversized/malformed frames; a snapshot-negotiation race; and catch-up of a transaction committed without any notification.

To run the native contract yourself, use dedicated disposable databases/services and export:

```text
EDITOR_NATIVE_TESTS=true
EDITOR_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/your_dedicated_test_database
EDITOR_TEST_DATABASE_USER=your_test_user
EDITOR_TEST_DATABASE_PASSWORD=<local secret>
EDITOR_TEST_REDIS_PORT=56379
EDITOR_TEST_REDIS_PASSWORD=<local secret>
```

Then run `mvn clean verify`. The contract generates its own random JWT key. It does not truncate unrelated databases; each scenario creates independent users/documents. Test data remains in the dedicated database for inspection. The native suite is opt-in; missing Docker does not silently switch CI to a mock implementation.

Reports are generated under `target/surefire-reports`, `target/failsafe-reports`, and `target/site/jacoco`. These build artifacts are intentionally ignored. They may contain synthetic test details, so CI retention is limited to seven days.

## Frontend and browser checks

- `npm ci`, TypeScript checking, **10 Vitest tests**, and `npm run build`: **passed**. Prettier formatting and reproducible format/check commands are also included.
- Tests exercise seeded three-replica convergence, duplicates/tombstones, pending snapshot restoration, scalar-safe replacement, conflicting/invalid input, a 10,000-element reversed chain, retryable admission rejection, contiguous replay, gap rejection and pending-outbox snapshot recovery.
- Real in-app browser sessions with two distinct synthetic accounts: register, create, grant editor access, open same document, save `HELLO 🌍`, insert `X` and `Y` concurrently after `H`. Both displayed `HXYELLO 🌍`, watermark 9 and no pending edits.
- Disconnect the second session; first writes a further sentence. Reconnect recovered the identical 46-scalar document at watermark 46. New typing was disabled while disconnected.
- Presence showed two active sessions; permission level, text, connection state and persisted version were visible in both clients. The scripted two-instance check separately verified server-side viewer rejection.

These are targeted functional browser checks, not a complete accessibility audit, all-browser compatibility matrix or exhaustive IME test. IME buffering is implemented but has not been exercised with every OS input method. No screenshot is fabricated as a live deployment.

## Two-instance smoke and persisted sample

Two separate backend JVMs shared the dedicated demo PostgreSQL database and Redis on the same Windows host. `SMOKE_BASE_A=http://127.0.0.1:8080` and `SMOKE_BASE_B=http://127.0.0.1:8082`, then `node scripts/smoke.mjs`: **PASS**.

Checks: concurrent Unicode insert, cross-instance delivery, original-watermark duplicate ACK, reconnect replay, HTTP snapshot fallback, equal materialized states from both instances, and edit denial after changing a user to VIEWER. The script creates only synthetic accounts/documents and marks its own document deleted at the end.

One throughput sample accepted **128 operations in 780.63 ms = 163.97 operations/second**, batches of 16, one submitting client. Timing includes WebSocket round trips, PostgreSQL persistence and checkpoints, but excludes authentication/setup. This is a small loopback sample, not sustained throughput, p95/p99 latency or a horizontal-scaling claim. The output is JSON and regenerated by the smoke script.

## CRDT benchmark

Command after test compilation (use `:` instead of `;` for the classpath separator on Linux/macOS):

```sh
java -Xmx512m -XX:ActiveProcessorCount=4 -cp "target/test-classes;target/classes" dev.collab.crdt.CrdtBenchmark
```

Sequential chain operations are prepared before timing. Five warmups, 15 measured runs, median. Rendering and restoring are measured separately; snapshot construction is excluded from the restore timing. A volatile sink consumes results. No claim is made that this simple harness has JMH's isolation or statistical rigor.

| Elements | Apply median (ms) | Render median (ms) | Restore median (ms) |
|---:|---:|---:|---:|
| 1,000 | 2.523 | 0.780 | 4.725 |
| 10,000 | 6.299 | 1.206 | 8.538 |

The benchmark raises core limits to exercise 10,000 elements; application documents remain capped at 5,000 inserted nodes. Results are sensitive to JIT warmup, OS scheduling and other processes; the nonlinear relationship between these two measurements should not be interpreted as a complexity proof.

## Security/configuration verification

- OSV runtime scan: **119 unique Maven artifacts, zero reported findings**, checked 2026-09-10 19:37:06 UTC. `scripts/audit-runtime.mjs` queries OSV using public artifact coordinates; it sends no project code or secrets. Final rescans may have a later timestamp.
- npm audit reported **zero vulnerabilities** after dependency installation, including the development-only WebSocket smoke client. A clean-install scan is included in final verification.
- Gitleaks 8.30.1 staged-source scan: **no leaks found**. Git staged whitespace check: **passed**. Staged paths contain no actual `.env`, private key, `target`, `node_modules`, IDE files, logs, credentials or test data. `.env.example` contains documentation placeholders only. The scan covers the initial staged source, not historical commits (there are none).
- `docker compose --profile multi config --quiet`: **passed**, using a standalone Compose binary and ignored generated secrets. Docker build/up/container integration cannot be run on this host; they are defined in CI but remain unverified here.

## Warnings and issues encountered

1. An initial npm metadata request was reset by the remote registry. Subsequent npm installation resolved and locked dependencies successfully.
2. The community Redis runtime required a relative config filename because it uses MSYS path conventions. It was started with loopback binding and temporary credentials. It also reduced its maximum client count because of its local file-descriptor limit; this is not a production Redis capacity result.
3. An early WebSocket test with emoji exposed a zero-sized UTF-8 scratch buffer. Bounded buffers were restored and binary rejection remains in the text handler. The real Unicode transport regression now passes.
4. The two-instance test exposed an edit racing with snapshot negotiation. The gateway now requests resynchronization safely and reserves snapshot fallback for gaps exceeding 100 operations. The regression now passes.
5. Running the packaged JAR directly from `target` prevented Maven from replacing it on Windows. Demo processes now run isolated copies outside the repository.
6. Mockito/Spring test startup emitted the JDK dynamic-agent warning. Tests still passed; production code does not dynamically attach Mockito. Some exploratory runs emitted Hikari scheduler-delay warnings while the development host was busy.
7. Docker checks report unavailable, not passed. No GitHub CI/deployment result is fabricated.
8. A clean frontend install initially encountered a Windows file lock held by the running Vite native module. The task's Vite process was stopped and the clean install, type check, tests, build and audit then passed.
9. The final read-only check of the separate asset-platform repository found an unrelated uncommitted change in `docs/REVIEW.md`. It was not staged, reverted or otherwise modified by this build.

## Publication

The supplied build request ends in the middle of the Git identity command (`git config user.name "Anup`). The missing GitHub instructions/target confirmation were requested while implementation continued. The local repository is initialized on `main`, with reviewed source staged for an initial commit. No commit, origin remote or GitHub push has been created for this project. The existing asset-platform repository was left unchanged. Send the missing ending of the build request to complete publication.
