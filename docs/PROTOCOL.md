# Protocol v1

## Authentication and actor binding

REST uses a bearer JWT. `POST /api/v1/documents/{id}/connections` with `{ "actorId": null }` allocates an actor bound to the authenticated user and document. On reconnect, supply the existing actor UUID; ownership is rechecked and its generation is incremented. The response includes `ticket`, `actorId`, `nextSequence`, `maxLamport`, `expiresAt` (JWT session expiry), and `socketPath`.

Open a native browser socket:

```js
new WebSocket(socketUrl, ['collab.v1', `ticket.${ticket}`]);
```

Tickets contain 32 random bytes encoded base64url, are valid for 30 seconds and are consumed atomically during handshake. Only their SHA-256 digest is stored. The server selects `collab.v1`, never the ticket-bearing protocol. The exact allowed `Origin` is required, including for non-browser test clients. Do not log handshake headers at proxies/APM; that would expose the short-lived ticket. TLS/WSS is required beyond loopback development.

## Client envelope

```json
{
  "protocolVersion": 1,
  "type": "JOIN_DOCUMENT",
  "documentId": "00000000-0000-0000-0000-000000000010",
  "actorId": "00000000-0000-0000-0000-000000000001",
  "afterSequence": 0
}
```

The server compares both IDs against the trusted connection context. User IDs are not accepted as authority. Unknown JSON properties, duplicate object keys, trailing input and deep/oversized structures are rejected. There is no client-controlled class name or Jackson default typing.

| Type | Required fields beyond envelope | Fields that must be absent/null |
|---|---|---|
| JOIN_DOCUMENT / SYNC_REQUEST | `afterSequence` >= 0 | operations, presence |
| EDIT | operations array, 1–32 records | afterSequence, presence |
| PRESENCE | presence object | afterSequence, operations |
| LEAVE_DOCUMENT | none | afterSequence, operations, presence |

An insert operation is flat and immutable:

```json
{
  "type": "INSERT",
  "operationId": {"actor":"00000000-0000-0000-0000-000000000001","counter":1},
  "lamport": 1,
  "elementId": {"actor":"00000000-0000-0000-0000-000000000001","counter":1},
  "predecessorId": null,
  "value": "🌍",
  "targetId": null
}
```

For DELETE, `elementId`, `predecessorId` and `value` are null; `targetId` names an inserted element, and `lamport` exceeds the target's counter. Each inserted value must be one Unicode scalar, excluding unpaired surrogates. IDs use UUID actors and positive safe-integer counters up to 9,007,199,254,740,991. The backend enforces contiguous new per-actor operation sequences, increasing actor Lamport time and a maximum logical jump of 4,096 above the current document clock. Duplicate IDs are accepted only with identical content and unchanged authorization.

## Server envelope and ordering

Every response contains `protocolVersion`, `type`, `documentId`, random `connectionId`, and `data`.

`ACK.data.acknowledgments` is an array of `{operationId, serverSequence, duplicate}`. It is sent only after the append transaction returns successfully. An ACK means durable acceptance, not that every peer has received the operation or its dependencies have resolved. Remove those operation IDs from the retry outbox, but **do not advance the replay watermark from ACK**.

`SYNC_RESPONSE.data` contains `operations`, `throughSequence`, `documentSequence`, `hasMore` and current `role`. Each operation entry has `serverSequence`, immutable `operation` and audit `createdAt`. Apply entries strictly contiguously, deduplicate by operation ID and advance the watermark only after application. A duplicate history page at the current cursor is harmless; a gap requires recovery. Server page size is 100 operations. A client may request the next page; the gateway also continues on its next wake.

`SYNC_REQUIRED.data` names `snapshotWatermark` and role when a stored checkpoint exists and the replay gap exceeds 100. Fetch `/snapshot`, restore its materialized state, reapply all unacknowledged local operations, then send `SYNC_REQUEST` at the returned snapshot watermark. Existing operation IDs remain stable. Any EDIT racing with snapshot negotiation receives another SYNC_REQUIRED; it is not acknowledged. Recent small suffixes can replay directly even if a checkpoint exists.

`ERROR.data.code` is bounded. Examples: `INVALID_MESSAGE`, `ACCESS_DENIED`, `SESSION_EXPIRED`, `ACTOR_SEQUENCE_GAP`, `OPERATION_ID_CONFLICT`, `DOCUMENT_CAPACITY`, `RATE_LIMITED`, `RATE_LIMIT_UNAVAILABLE`, `SERVICE_UNAVAILABLE`. Invalid JSON closes with 1007, policy failures generally with 1008, oversized input with 1009, binary input with 1003, and internal transport failure with 1011. Capacity/identity errors stop the frontend and retain a draft for export. Rate-limit errors preserve pending edits and delay retries.

## Presence

`presence` is `{anchor: ElementId | null, focus: ElementId | null}`; anchors represent the element immediately before each selection endpoint. The UI converts UTF-16 textarea offsets to Unicode-scalar positions. Unknown/deleted cursor anchors do not affect document state. The current UI shows people/session status; remote cursor overlays are not implemented.

Server presence records include trusted user/actor IDs, connection ID and receipt time. Redis hash records expire/filter after inactivity; wall time is used only for presence freshness and auditing, never CRDT ordering. Browser selection updates are throttled to approximately 10 Hz and heartbeat every 10 seconds. Server limits are 20 presence messages/second/user. Presence responses contain `{users, available}`; Redis outages report unavailable. Presence is approximate and can be stale for up to about 30 seconds after an abrupt disconnect.

## Limits and client recovery

- Incoming text messages: 32 KiB UTF-8; binary messages rejected. Outbound send buffer: 1 MiB and a 5-second send limit.
- 240 WebSocket messages/second/user and 240 submitted operations/second/user. Fixed-window rate limits can allow bursts around a boundary; they are not smooth token buckets.
- 30 connection tickets/minute/user; 60 handshakes/minute/source IP; 20 auth requests/minute/source IP; 300 ordinary API requests/minute/user.
- The app ignores forwarded-client-IP headers. Deployments behind a proxy need a reviewed trusted-proxy strategy; users sharing one proxy IP may share auth/handshake limits.
- Reconnect backoff starts at 500 ms and caps at 15 seconds. Pending operations are retained in memory, resent in batches and removed by ACK. A lost ACK is retried after about five seconds.
- New typing is disabled while disconnected or recovering. JWT expiry requires sign-in again. There is no durable offline outbox or refresh-token flow.
