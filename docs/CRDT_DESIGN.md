# CRDT design (written before implementation)

## Choice and scope

Implement an RGA-style replicated sequence, independently in plain Java and in the TypeScript client. No CRDT library is used. The design uses immutable predecessor links, ordered sibling sets, tombstones and deferred dependency resolution. This is a text-only, bounded portfolio implementation, not a claim of formal verification or compatibility with every RGA variant. The original RGA work is [Roh et al., Replicated abstract data types](https://doi.org/10.1016/j.jpdc.2010.12.011).

## Identity, logical time and operations

An operation ID is `(actor UUID, per-actor sequence)`. Each actor is issued by the server for one authenticated user and document. The server checks actor ownership, connection generation and contiguous new operation sequences; it never treats a supplied actor ID as authorization.

An inserted element ID is `(actor UUID, Lamport counter)`. Its counter equals the insert's Lamport time. Insert operations carry their operation ID, element ID, logical predecessor (null for the root) and exactly one Unicode scalar. Delete operations have their own operation ID and name the target element. A deletion never removes the target's position/children.

Lamport time advances beyond all observed logical counters before a local edit. A reference's counter must be lower than the operation's Lamport time, including when the referenced element has not arrived. This makes predecessor cycles invalid without depending on delivery order. Actor sequences identify operations; Lamport counters order siblings. Counters fit JavaScript's safe integer range. No wall-clock timestamp orders text: wall clocks drift, can move backwards and cannot establish causality.

## Deterministic ordering

Each node owns an ordered set of children. Siblings sort by descending Lamport counter, then descending canonical lowercase actor UUID. Render a depth-first preorder from the root, omitting tombstoned values but traversing their children. A later local insertion after a predecessor sorts before that predecessor's older successor. Concurrent siblings use the actor tie-break, regardless of message arrival order. Subsequent insertions can interleave: this design does not promise rich-text intention preservation or the non-interleaving behavior of more advanced algorithms.

## Missing dependencies and duplicates

Hash maps index nodes, operation signatures and pending inserts/deletes. Unknown predecessors and targets are buffered; they are never silently discarded. Integrating a predecessor iteratively unlocks waiting descendants and deletions. A duplicate ID with identical canonical content is a no-op; conflicting reuse is rejected. A second insertion claiming an already reserved element ID is rejected.

All replicas converge for the same finite set of valid, uniquely identified operations whose dependencies eventually arrive, within their configured resource bounds. The immutable tree relation, common sibling comparator and monotonic tombstones determine the result. Tests will permute, duplicate and delay operations across multiple replicas, including independently generated concurrent edits. Such tests are evidence for the implementation, not a mathematical proof covering every possible execution or Byzantine input.

## Bounds, snapshots and recovery

Bound total operations, inserted nodes and pending dependencies per document. Reject an operation before adding it to deduplication state if it exceeds a limit. A capacity rejection is not an acknowledged edit: callers must synchronize/retry or surface the failure. Resource limits can prevent arbitrary delivery schedules from making progress; convergence is conditional on eventual delivery of the accepted operation set.

Snapshots contain materialized nodes (including tombstones), pending operations, operation signatures and logical time. Restoring indexes does not replay the historical operation log. PostgreSQL stores the snapshot watermark and retains the operation log; load a snapshot plus the later suffix. Snapshots and deduplication state still grow with retained history. No unsafe operation deletion or tombstone collection is attempted without a causal-stability protocol.

The client disables new typing while disconnected. It retains unacknowledged edits in memory and resends the same immutable IDs after reconnect. Snapshot fallback rebuilds the server baseline and reapplies that pending set, rather than silently overwriting it. Closing/reloading the tab can lose unacknowledged edits; durable offline-first editing is not claimed.

## Server ordering is a persistence concern

PostgreSQL assigns a contiguous per-document server watermark under a document-row lock. That sequence supports replay and snapshots; it does not resolve text order. Redis Pub/Sub announces committed edits between instances, while periodic database catch-up repairs missed notifications. Redis is not the operation log. Authorization is rechecked on edits and catch-up, including permission revocation and token expiry.

## Expected costs and limitations

Operation lookup and duplicate detection are expected O(1). Sibling insertion is O(log s); rendering is O(n), using iterative traversal rather than recursive stack depth or repeated string concatenation. Dependency resolution is proportional to the operations unlocked plus sibling insertion costs. Node/signature/tombstone storage is O(n + operations). The initial backend will reconstruct bounded state from a snapshot plus suffix inside its transaction; this is a documented per-edit O(state + suffix) cost, not a high-throughput claim. Benchmarks will separate core algorithm costs from that persistence overhead.
