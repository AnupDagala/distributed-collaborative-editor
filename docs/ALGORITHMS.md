# Data structures and actual costs

Let n be inserted nodes including tombstones, h retained operations, s siblings under one predecessor, p pending operations, k the operations unlocked by an arriving dependency, and r operations after the latest checkpoint. Hash-map costs below are expected, not adversarial worst-case guarantees.

| Action | Java core | TypeScript core |
|---|---|---|
| Operation/element lookup | O(1) expected | O(1) expected |
| Duplicate comparison | O(1) bounded-signature work | O(1) bounded-signature work |
| Insert known predecessor | O(log s), ordered tree set | O(log s) search + O(s) array movement |
| Delete known target | O(1), monotonic tombstone | O(1), monotonic tombstone |
| Buffer unknown dependency | O(1) expected | O(1) expected |
| Resolve dependencies | O(k + sum log sibling counts) | O(k + total sibling-array movement) |
| Render / collect visible IDs | O(n), iterative DFS | O(n), iterative DFS |
| Materialize deterministic snapshot | O(n log n + h log h + p log p) sorting | Same sorting costs |
| Restore snapshot indexes | O(n + h + p + sum log sibling counts) | O(n + h + p + sibling-array movement) |
| Memory | O(n + h + p) | O(n + h + p) |

Java indexes nodes by element ID and operation signatures by operation ID. Each node has a reverse-order `TreeSet` of child IDs; a root set acts as the virtual predecessor. The owner map reserves IDs for pending inserts as well as integrated nodes. A dependency map indexes both pending inserts and deletes. An iterative queue drains newly available dependencies. An explicit stack of iterators renders the tree, avoiding recursive stack overflow on long chains and avoiding repeated concatenation of immutable strings.

In the browser, scalar arrays are compared using a longest common prefix/suffix. The replaced range becomes tombstone operations and newly chained insertions. The diff and render cost O(n); an atomic local batch is first applied to a restored candidate snapshot, adding snapshot sorting/copy costs. This deliberately favors consistency and bounded simplicity over high-volume input performance. It does not provide an indexed text rope or grapheme-aware editing.

The server reloads a materialized snapshot and replays r operations for every append batch. Consequently transaction cost includes O(state restoration + r insertion work), even for a one-character edit. The document-row lock serializes this work for a document. Snapshot frequency bounds the normal suffix, but snapshot data and retained signatures still grow with history. The benchmark separates plain core apply/render/restore from persisted WebSocket throughput so these costs are not conflated.

Pending caps can reject a delivery schedule that would converge with unlimited memory. Rejection happens before adding the operation to deduplication state, making retry possible when dependencies arrive. Resource limits are admission control; they are not causal garbage collection. No tombstone or operation-log deletion is claimed safe without acknowledgments/causal stability across replicas.
