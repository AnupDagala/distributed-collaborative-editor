# Explaining this implementation

1. **Why CRDT instead of database locking?** Users edit replicas before the server sees those changes. A row lock can order commits but cannot recover what a stale numeric offset meant. The CRDT uses stable predecessor/target IDs. I still lock a document row to atomically assign a replay watermark and commit operation/actor/snapshot records.

2. **CRDT versus Operational Transformation?** OT generally transforms an operation against concurrent operations in a shared ordering/context. This implementation keeps a deterministic predecessor tree and tombstones instead. That makes duplicate/reordered delivery straightforward to test, but pays for retained metadata and does not guarantee all rich-text insertion intentions.

3. **How do concurrent inserts converge?** Inserts after the same predecessor become siblings. All replicas use descending Lamport counter and canonical lowercase UUID tie-breaking, then the same depth-first traversal. Java UUID's built-in signed comparison would differ from browser string ordering, so I implemented the comparator explicitly.

4. **What does the Lamport clock provide?** It lets a later local insert sort before an older successor and enforces that dependency links point to earlier logical counters. It captures ordering constraints, not real elapsed time or a total causal history. The UUID breaks concurrent ties.

5. **Why not timestamps?** Clocks drift and can move backwards. Two equal timestamps also need a deterministic tie-break. Wall time is used for token expiry, audit time and presence freshness, never visible text ordering.

6. **How are duplicates handled?** The core stores each operation ID's canonical signature. PostgreSQL also has a unique `(document, actor, operation-sequence)` key. Identical replay returns the original watermark; conflicting reuse rejects the transaction. Authorization is checked before returning a duplicate ACK.

7. **How does reconnect work?** The browser retains a contiguous replay cursor and in-memory unacknowledged operations. A new ticket fences its actor's previous connection generation. Replay fills missing history; a large gap uses an HTTP snapshot plus reapplication of the pending outbox. ACKs only clear retry work, so a high ACK cannot skip someone else's earlier edit.

8. **Why isn't Redis authoritative?** Pub/Sub is non-durable; disconnected consumers lose notifications. I publish only commit hints and always fetch accepted operations from PostgreSQL. The regression suite includes a committed database transaction with no notification and checks that a connected client catches up.

9. **How do multiple instances communicate?** A source-tagged hint wakes peers; peers read ordered history and never republish. Each instance also polls the database periodically. A two-process smoke test passed locally. That proves the exercised cross-instance path, not capacity scaling or failover performance.

10. **How do snapshots help?** A snapshot stores materialized nodes, pending dependencies, signatures and the watermark, so loading rebuilds indexes and replays only the suffix. It is not a rendered-text snapshot that would lose IDs/tombstones. Snapshots still grow with history; they do not solve safe garbage collection.

11. **What is the tombstone trade-off?** Deletes are monotonic and late descendants remain attached, but deleted nodes consume memory and checkpoint space. I cap document history and do not collect tombstones without a causal-stability protocol.

12. **What is the security model?** BCrypt login produces short-lived JWTs; every document operation checks ACLs. Native WebSockets use one-time tickets through a subprotocol, not a query JWT. The server binds actors and checks generation/expiry/role again on edits and catch-up. Origin checks, bounds, rate limits, plain React rendering and safe logs address distinct attack surfaces. This is not end-to-end encryption.

13. **What is the main bottleneck?** Reconstructing document state while holding the per-document append lock. The shared socket-repair loop can also be delayed by I/O. The small benchmark demonstrates behavior at bounded sizes; I would not extrapolate it to thousands of active writers.

14. **What would change for millions of users?** Partition ownership/work by document, build a versioned cache with transactional invalidation, introduce fair bounded gateway scheduling and an indexed text structure, add causal compaction and a durable client outbox, then load-test distributions of hot/cold documents and inject failures. Operational identity, tenant quotas, backups and observability would need equal attention. Raising configuration limits alone would not make this implementation ready for that scale.

Two concrete bugs found by verification are worth discussing: a too-small WebSocket scratch buffer broke emoji frames; and snapshot negotiation initially rejected an edit racing with a new checkpoint. Both were fixed and retained as real transport regressions instead of being hidden by a text-only demo.
