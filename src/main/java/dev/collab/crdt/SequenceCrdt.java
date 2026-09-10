package dev.collab.crdt;

import java.util.*;

/** Single-owner mutable replica. Synchronization belongs to its caller, not the algorithm. */
public final class SequenceCrdt {
  public enum Result {
    APPLIED,
    PENDING,
    DUPLICATE
  }

  public record Limits(int nodes, int operations, int pending) {
    public Limits {
      if (nodes < 1 || operations < nodes || pending < 1)
        throw new IllegalArgumentException("Invalid limits");
    }

    public static Limits document() {
      return new Limits(5000, 10000, 256);
    }
  }

  public record NodeState(
      Id id, Id predecessor, String value, boolean deleted, Id insertOperationId) {}

  public record Seen(Id operationId, String signature) {}

  public record State(
      List<NodeState> nodes, List<Operation> pending, List<Seen> seen, long maxLamport) {
    public State {
      nodes = List.copyOf(nodes);
      pending = List.copyOf(pending);
      seen = List.copyOf(seen);
    }
  }

  private static final class Node {
    final Id id, predecessor, insertOperationId;
    final String value;
    boolean deleted;
    final NavigableSet<Id> children = new TreeSet<>(Comparator.reverseOrder());

    Node(NodeState s) {
      id = s.id();
      predecessor = s.predecessor();
      value = s.value();
      deleted = s.deleted();
      insertOperationId = s.insertOperationId();
    }

    NodeState state() {
      return new NodeState(id, predecessor, value, deleted, insertOperationId);
    }
  }

  private final Limits limits;
  private final Map<Id, Node> nodes = new HashMap<>();
  private final NavigableSet<Id> roots = new TreeSet<>(Comparator.reverseOrder());
  private final Map<Id, String> seen = new HashMap<>();
  private final Map<Id, Id> owners = new HashMap<>();
  private final Map<Id, List<Operation>> waiting = new HashMap<>();
  private int pendingCount;
  private long maxLamport;

  public SequenceCrdt() {
    this(Limits.document());
  }

  public SequenceCrdt(Limits limits) {
    this.limits = limits;
  }

  public Result apply(Operation op) {
    Objects.requireNonNull(op, "operation");
    String signature = op.signature();
    String existing = seen.get(op.operationId());
    if (existing != null) {
      if (!existing.equals(signature))
        throw new IllegalArgumentException("Conflicting operation ID");
      return Result.DUPLICATE;
    }
    if (seen.size() >= limits.operations())
      throw new IllegalStateException("Operation capacity reached");
    boolean insert = op.type() == Operation.Type.INSERT;
    if (insert) {
      if (owners.containsKey(op.elementId()))
        throw new IllegalArgumentException("Conflicting element ID");
      if (owners.size() >= limits.nodes()) throw new IllegalStateException("Node capacity reached");
    }
    Id dependency = insert ? op.predecessorId() : op.targetId();
    boolean missing = dependency != null && !nodes.containsKey(dependency);
    if (missing && pendingCount >= limits.pending())
      throw new IllegalStateException("Pending capacity reached");
    // Nothing above this point mutates state: a rejected operation remains retryable.
    seen.put(op.operationId(), signature);
    if (insert) owners.put(op.elementId(), op.operationId());
    maxLamport = Math.max(maxLamport, op.lamport());
    if (missing) {
      waiting.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(op);
      pendingCount++;
      return Result.PENDING;
    }
    integrate(op);
    return Result.APPLIED;
  }

  private void integrate(Operation first) {
    ArrayDeque<Operation> queue = new ArrayDeque<>();
    queue.add(first);
    while (!queue.isEmpty()) {
      Operation op = queue.removeFirst();
      if (op.type() == Operation.Type.DELETE) {
        nodes.get(op.targetId()).deleted = true;
      } else {
        Node node =
            new Node(
                new NodeState(
                    op.elementId(), op.predecessorId(), op.value(), false, op.operationId()));
        nodes.put(node.id, node);
        (node.predecessor == null ? roots : nodes.get(node.predecessor).children).add(node.id);
        List<Operation> unlocked = waiting.remove(node.id);
        if (unlocked != null) {
          pendingCount -= unlocked.size();
          queue.addAll(unlocked);
        }
      }
    }
  }

  public List<Id> visibleIds() {
    List<Id> visible = new ArrayList<>();
    walk(
        node -> {
          if (!node.deleted) visible.add(node.id);
        });
    return List.copyOf(visible);
  }

  public String text() {
    StringBuilder result = new StringBuilder();
    walk(
        node -> {
          if (!node.deleted) result.append(node.value);
        });
    return result.toString();
  }

  private void walk(java.util.function.Consumer<Node> visit) {
    ArrayDeque<Iterator<Id>> stack = new ArrayDeque<>();
    stack.push(roots.iterator());
    while (!stack.isEmpty()) {
      Iterator<Id> next = stack.peek();
      if (!next.hasNext()) {
        stack.pop();
        continue;
      }
      Node node = nodes.get(next.next());
      visit.accept(node);
      if (!node.children.isEmpty()) stack.push(node.children.iterator());
    }
  }

  public int pendingCount() {
    return pendingCount;
  }

  public int operationCount() {
    return seen.size();
  }

  public long maxLamport() {
    return maxLamport;
  }

  public State snapshot() {
    return new State(
        nodes.values().stream()
            .map(Node::state)
            .sorted(Comparator.comparing(NodeState::id))
            .toList(),
        waiting.values().stream()
            .flatMap(Collection::stream)
            .sorted(Comparator.comparing(Operation::operationId))
            .toList(),
        seen.entrySet().stream()
            .map(e -> new Seen(e.getKey(), e.getValue()))
            .sorted(Comparator.comparing(Seen::operationId))
            .toList(),
        maxLamport);
  }

  public static SequenceCrdt restore(State state, Limits limits) {
    SequenceCrdt replica = new SequenceCrdt(limits);
    if (state.nodes().size() > limits.nodes()
        || state.seen().size() > limits.operations()
        || state.pending().size() > limits.pending()
        || state.maxLamport() < 0
        || state.maxLamport() > Id.MAX_COUNTER)
      throw new IllegalArgumentException("Invalid snapshot bounds");
    for (Seen item : state.seen()) {
      if (item.signature() == null
          || replica.seen.put(item.operationId(), item.signature()) != null)
        throw new IllegalArgumentException("Invalid snapshot signatures");
    }
    for (NodeState node : state.nodes()) {
      Operation original =
          Operation.insert(
              node.insertOperationId(), node.id().counter(), node.predecessor(), node.value());
      if (!original.elementId().equals(node.id())
          || !original.signature().equals(replica.seen.get(node.insertOperationId()))
          || node.id().counter() > state.maxLamport()
          || replica.nodes.put(node.id(), new Node(node)) != null)
        throw new IllegalArgumentException("Invalid snapshot node");
      replica.owners.put(node.id(), node.insertOperationId());
    }
    for (Node node : replica.nodes.values()) {
      if (node.predecessor == null) replica.roots.add(node.id);
      else {
        Node parent = replica.nodes.get(node.predecessor);
        if (parent == null) throw new IllegalArgumentException("Invalid snapshot parent");
        parent.children.add(node.id);
      }
    }
    Set<Id> pendingIds = new HashSet<>();
    for (Operation op : state.pending()) {
      Id dependency = op.type() == Operation.Type.INSERT ? op.predecessorId() : op.targetId();
      if (dependency == null
          || replica.nodes.containsKey(dependency)
          || !op.signature().equals(replica.seen.get(op.operationId()))
          || op.lamport() > state.maxLamport()
          || !pendingIds.add(op.operationId()))
        throw new IllegalArgumentException("Invalid pending snapshot operation");
      if (op.type() == Operation.Type.INSERT
          && replica.owners.putIfAbsent(op.elementId(), op.operationId()) != null)
        throw new IllegalArgumentException("Invalid snapshot element owner");
      replica.waiting.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(op);
      replica.pendingCount++;
    }
    if (replica.owners.size() > limits.nodes())
      throw new IllegalArgumentException("Invalid snapshot capacity");
    replica.maxLamport = state.maxLamport();
    return replica;
  }
}
