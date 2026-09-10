# Threat model

## Assets and trust boundaries

Assets include document text/history, memberships, password hashes, JWT signing material, one-time tickets, actor sequence bindings and presence metadata. Internet/browser inputs are untrusted. A JWT establishes a user identity, not access to any particular document. An actor UUID is an addressable editing identity, not an authentication credential.

The browser-to-gateway boundary requires origin validation, an unexpired consumed ticket and per-document authorization. The gateway-to-service boundary carries a trusted connection context plus untrusted operation DTOs. PostgreSQL is authoritative; Redis messages are advisory and may be duplicated or lost. The default deployment assumes the private container network, Redis/PostgreSQL credentials and operating system are trusted. A compromised database operator can change state; checksums detect accidental corruption, not a malicious operator who can rewrite both payload and checksum.

## Threats and controls

| Threat | Implemented controls | Residual risk / limits |
|---|---|---|
| Guessed document UUID | Membership/ownership checks on metadata, snapshot, history, ticket, join and edit | Document existence may be distinguishable via 403/404; UUID secrecy is not a control |
| Forged actor/user ID | Server-generated actor bound to user/document; trusted ticket context; compare envelope/operation actor; generation fencing | An authorized editor may intentionally change/delete text |
| Forged operation ID / replay | Per-document unique operation key; checksum comparison; contiguous new actor sequences; dedup checks under document lock | Legitimate duplicate traffic consumes rate budget; no cryptographic signatures per operation |
| Replay after revocation | ACL checks precede duplicate handling; replay/idle polling rechecks authorization | An edit committed before revocation remains in history; already received content cannot be recalled |
| JWT theft | Short lifetime, issuer/audience/subject checks, no URL token, HTTPS deployment requirement | Session storage is vulnerable to same-origin XSS; no instant JWT revocation/refresh flow |
| Cross-site WebSocket hijacking | Exact Origin policy; non-cookie one-time ticket; only application protocol selected | Malicious non-browser clients can forge Origin but still need a valid ticket |
| Oversized/malformed JSON | HTTP byte wrapper including chunked input; bounded WebSocket buffers; strict properties/duplicate-key/trailing-token checks; depth/number/string bounds | Some rejected requests use framework-generated HTTP error bodies; stress testing is limited |
| Unsafe deserialization | Flat records/enums, UUID/numeric/scalar validation, no arbitrary polymorphic typing | Library defects remain possible; dependencies need regular updates |
| Operation/pending flooding | Redis quotas, finite nodes/ops/pending/actors/documents/members, monotonic clocks and bounded jumps | Fixed-window bursts; multiple accounts/IPs; intentional saturation by authorized editors |
| Slow socket / memory abuse | Per-process/per-document connection caps, 32 KiB input, 1 MiB outbound queue, 5-second send timeout | Shared scheduler may be delayed by slow I/O; no distributed connection quota |
| XSS through text/title | React text rendering, textarea values, no raw HTML rendering, production CSP | Trusted dependencies/extensions or future unsafe UI changes can undermine this |
| SQL injection | Parameterized JDBC; JPA repository methods; typed pagination/IDs | Database account remains powerful in the development deployment |
| Redis exposure / forged Pub/Sub hints | No host Redis port; password; source identity/no echo; hint triggers authorized PostgreSQL reads only | Anyone with Redis credentials can observe presence, disrupt quotas or cause wakeups; use deployment Redis ACLs/TLS |
| PostgreSQL exposure | No host DB port; credentials from environment; application ACLs and constraints | Plaintext content is visible to DB operators; production encryption/backup/access controls are external |
| Presence leakage | Check document access before reads/updates; trusted user/actor IDs; TTL; no cursor DB log | Authorized document members see collaborator IDs; presence is approximate and temporarily stale |
| Secret leakage in logs/repos | Generated ignored local `.env`, bounded error codes, metadata-only logging, Gitleaks, CI reports without credentials | Proxy/APM/header debug logging could leak tickets/JWTs; never enable payload tracing on private data |
| Redis/network outage | Failed quotas deny work; committed history is independent of Pub/Sub; periodic database repair and snapshot fallback | Redis remains an availability dependency for authentication/new edits; no claim of offline-first operation |
| Snapshot corruption / unsafe compaction | Snapshot and operation checksums; fail closed on corrupted stored state; retain operations/tombstones | No automatic repair tooling or causal tombstone GC; corruption may require an operator recovery procedure |

## Explicitly outside the current implementation

Email verification, password reset, account lifecycle, tenant-wide billing quotas, public registration bot defense, multi-region consistency, end-to-end encryption, malware scanning, rich-text sanitization, backup-restore automation, legal data-erasure workflows and disaster-recovery SLOs. These are deployment/product work, not properties inferred from unit tests.
