# Architecture and consistency boundaries

## Components

`SequenceCrdt` is a single-owner in-memory replica with no transport, database or Spring code. `DocumentLifecycleService` owns creation, membership and deletion; `DocumentAccess` centralizes document locks and ACL checks. `CollaborationService` coordinates actor/ticket binding, atomic append and replay through `OperationStore`, which restores/checksums materialized state and reads the log. Immutable DTOs live in `CollaborationModels`. `CollaborationGateway` authenticates metadata against its trusted connection context, sends bounded frames and maintains local socket cursors. `RedisNotifications` transports commit hints; `PresenceService` stores ephemeral selection anchors. Controllers only accept/return DTOs.

The browser has its own TypeScript replica and synchronization controller. UI text is a projection of that replica. A textarea diff is translated into insert/delete operations over stable IDs. The client never sends the whole text as an authoritative replacement.

## Durable append transaction

```mermaid
sequenceDiagram
  participant C as Client
  participant G as Gateway
  participant S as CollaborationService
  participant P as PostgreSQL
  participant R as Redis
  C->>G: EDIT with actor-bound IDs
  G->>S: append(trusted connection, batch)
  S->>P: lock document row; check ACL + actor generation
  S->>P: load snapshot + ordered suffix
  S->>S: verify IDs, clocks, duplicates, limits; apply CRDT
  S->>P: append operations; advance watermark + actor; checkpoint if due
  P-->>S: transaction commits
  S-->>R: best-effort source/document/watermark hint
  S-->>G: durable acknowledgments
  G-->>C: ACK (does not move replay cursor)
  G->>P: fetch contiguous operation history
  G-->>C: SYNC_RESPONSE (moves replay cursor)
```

The document row is locked before actor/ticket records everywhere that mutates them. Sharing, revocation and deletion also acquire that lock, so their order relative to an accepted edit is well defined. New actor sequences must be contiguous; duplicate IDs are looked up before sequence validation and must match their stored checksum. ACL checks happen before deduplication, so a viewer cannot submit an old edit as a loophole.

Each log entry receives a contiguous document-local server sequence. This is a replay cursor, not a conflict-resolution rule. Concurrent sibling order depends only on logical element IDs. A failed batch rolls back the log, actor position, document watermark and any new snapshots together.

The JPA transaction manager binds JDBC to the same datasource/transaction. JPA owns document version/locking and user lifecycle. SQL uses placeholders; entities are never serialized to clients. Read snapshots capture a watermark and select only checkpoints and log entries at or below it, so later appends do not leak into a mixed-version projection.

## Recovery and notification loss

PostgreSQL is the source of truth. `RedisNotifications` emits only `{sourceInstance, documentId, serverSequence}` after commit and catches publication failures. Listener callbacks wake local gateways without republishing. Every gateway also runs periodic database repair; the lost-notification integration test commits a valid operation transaction without invoking any notification path and verifies delivery to an already connected socket.

For replay gaps exceeding 100 operations when a checkpoint exists, the gateway sends `SYNC_REQUIRED`. The client fetches a current HTTP snapshot, restores it, reapplies its in-memory unacknowledged set and resumes from the snapshot watermark. A racing edit while recovery is required triggers another synchronization request; it is not acknowledged or silently dropped. Recent active clients replay small suffixes across checkpoint boundaries without unnecessary snapshot transfers.

Reconnect reuses the tab's actor ID, verifies its owner/document binding and increments a generation. Old sockets/tickets cannot submit new edits with that actor. A new tab gets a new actor. Presence uses connection IDs so one socket closing cannot erase another socket's presence.

## Resource and deployment boundaries

Gateway sends use Spring's concurrent session decorator, a 5-second send limit and 1 MiB queue cap. Input messages and Tomcat's UTF-8 scratch buffers are bounded at 32 KiB; `TextWebSocketHandler` rejects binary messages. A zero-sized binary scratch buffer broke supplementary Unicode in an early integration run; the UTF-8 regression is retained.

Per-process scheduling is intentionally simple: a 250 ms wake loop with full repair roughly every second, plus 30-second ticket cleanup/pending-gauge sampling. Slow queries or blocked sends can extend those intervals across clients. This is a documented bottleneck, not a real-time SLA. Connection limits bound the loop's work. Production evolution should partition gateway work per document with a bounded executor and fair scheduling.

The Compose setup is a development deployment with internal PostgreSQL/Redis and loopback application ports. Nginx serves the frontend and proxies same-origin API/WebSocket traffic. The optional second backend shares state but the default frontend proxy points to backend A; the two-instance smoke test connects directly to A and B. Sticky sessions are not needed for reconnect correctness, but load-balancer/TLS configuration is outside this repository's measured scope.
