# Security policy

This repository demonstrates security controls in a bounded collaborative editor. It has not undergone a penetration test or production security assessment. Do not put real confidential documents into a public deployment without reviewing the [threat model](docs/THREAT_MODEL.md), operational configuration and dependencies.

## Reporting

For a suspected vulnerability, use the repository's GitHub private vulnerability-reporting feature if the maintainer has enabled it. If that feature is unavailable, open an issue asking for a private reporting channel without posting exploit details, credentials or document contents. No private contact address is invented here. Include affected commit/version, prerequisites, minimal reproduction using synthetic data and expected versus actual authorization behavior.

## Defaults and deployment responsibilities

- BCrypt cost 12; passwords limited to 72 UTF-8 bytes; generic login failures with dummy hash verification for unknown users.
- JWT issuer/audience/subject/expiry checks. Tokens last 15 minutes. Spring's normal JWT clock-skew validation applies to REST; socket session checks use the exact expiry. There are no refresh tokens or instant global JWT revocation.
- Private-by-default documents and server-enforced VIEWER/EDITOR/OWNER ACLs. Only an owner manages membership or deletes documents.
- Short-lived, one-time hashed WebSocket tickets and actor generation fencing. Ticket-bearing subprotocol request headers must be redacted by infrastructure logs.
- Exact origin allowlist, safe JSON schema, binary rejection, incoming message/body caps, bounded operation/node/pending state, rate limits and slow-consumer send bounds.
- Redis rate-limit failures deny new protected work instead of silently removing abuse protection. Infrastructure should use separate credentials/ACLs and TLS when crossing trust boundaries.
- Generated secrets live in an ignored `.env` for local Compose only. Production must obtain them from a secret manager or deployment environment. Never reuse test credentials or copy secrets into images, source, issue reports or screenshots.
- Production frontend CSP prohibits inline scripts/objects/framing. React/textarea render document text safely. Session storage remains readable by any successful same-origin XSS, and browser extensions or a compromised device remain outside these controls.
- PostgreSQL and Redis are not exposed as host ports by Compose. Application ports bind loopback. Public service requires a reviewed HTTPS/WSS reverse proxy, identity lifecycle, backups, monitoring and incident response.

## Data and retention

The server can read plaintext document content; this is not end-to-end encrypted storage. Use encrypted disks/backups and access-controlled database accounts in a real deployment. The development database account also runs Flyway; production should separate migration authority from the least-privilege application account.

Deleting a document marks it inaccessible through the application. It does not physically erase operations, snapshots, memberships or backups. No data-erasure compliance guarantee is claimed. A reviewed retention/deletion process and causal-stability design are future work. Presence lives in Redis with TTL-based cleanup and is not part of durable document history.

## Verification

Tests cover authorization, actor spoofing, duplicate/conflicting IDs, replay, strict JSON, origins, binary/oversized frames, rate-limit failure, snapshot checksums and notification loss. `scripts/audit-runtime.mjs`, `npm audit` and Gitleaks are wired into CI. Their coverage is limited to known advisories and recognizable secret patterns; manual design/code review remains necessary. Exact local results and unrun checks are recorded in [VERIFICATION.md](docs/VERIFICATION.md).
