export interface Id {
  actor: string;
  counter: number;
}
export interface Operation {
  type: "INSERT" | "DELETE";
  operationId: Id;
  lamport: number;
  elementId: Id | null;
  predecessorId: Id | null;
  value: string | null;
  targetId: Id | null;
}
export interface NodeState {
  id: Id;
  predecessor: Id | null;
  value: string;
  deleted: boolean;
  insertOperationId: Id;
}
export interface State {
  nodes: NodeState[];
  pending: Operation[];
  seen: { operationId: Id; signature: string }[];
  maxLamport: number;
}
interface Node extends NodeState {
  children: Id[];
}
export const key = (id: Id | null): string =>
  id ? `${id.actor}:${id.counter}` : "-";
const compare = (a: Id, b: Id): number =>
  a.counter === b.counter
    ? a.actor < b.actor
      ? -1
      : a.actor === b.actor
        ? 0
        : 1
    : a.counter - b.counter;
export const signature = (op: Operation): string =>
  `${op.type}|${key(op.operationId)}|${op.lamport}|${key(op.elementId)}|${key(op.predecessorId)}|${key(op.targetId)}|${op.value ?? ""}`;
const validId = (id: Id | null): id is Id =>
  !!id &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
    id.actor,
  ) &&
  Number.isSafeInteger(id.counter) &&
  id.counter > 0;
function validate(op: Operation): void {
  if (
    !validId(op.operationId) ||
    !Number.isSafeInteger(op.lamport) ||
    op.lamport < 1
  )
    throw Error("Invalid operation identity");
  if (op.type === "INSERT") {
    const scalar = op.value?.codePointAt(0);
    if (
      !validId(op.elementId) ||
      op.elementId.actor !== op.operationId.actor ||
      op.elementId.counter !== op.lamport ||
      op.targetId !== null ||
      !op.value ||
      Array.from(op.value).length !== 1 ||
      scalar === undefined ||
      (scalar >= 0xd800 && scalar <= 0xdfff)
    )
      throw Error("Invalid insert");
    if (
      op.predecessorId !== null &&
      (!validId(op.predecessorId) || op.predecessorId.counter >= op.lamport)
    )
      throw Error("Invalid predecessor");
  } else if (op.type === "DELETE") {
    if (
      !validId(op.targetId) ||
      op.targetId.counter >= op.lamport ||
      op.elementId !== null ||
      op.predecessorId !== null ||
      op.value !== null
    )
      throw Error("Invalid delete");
  } else throw Error("Invalid operation type");
}
export class SequenceCrdt {
  private nodes = new Map<string, Node>();
  private roots: Id[] = [];
  private seen = new Map<string, { operationId: Id; signature: string }>();
  private owners = new Set<string>();
  private waiting = new Map<string, Operation[]>();
  pendingCount = 0;
  maxLamport = 0;
  constructor(
    private limits = { nodes: 5000, operations: 10000, pending: 256 },
  ) {}
  get nodeCount(): number {
    return this.owners.size;
  }
  get operationCount(): number {
    return this.seen.size;
  }
  apply(input: Operation): "APPLIED" | "PENDING" | "DUPLICATE" {
    validate(input);
    const op = structuredClone(input); // Freeze ownership at the replica boundary.
    const sig = signature(op),
      existing = this.seen.get(key(op.operationId));
    if (existing) {
      if (existing.signature !== sig) throw Error("Conflicting operation ID");
      return "DUPLICATE";
    }
    if (this.seen.size >= this.limits.operations)
      throw Error("Document operation capacity reached");
    const insert = op.type === "INSERT";
    if (
      insert &&
      (this.owners.has(key(op.elementId)) ||
        this.owners.size >= this.limits.nodes)
    )
      throw Error("Element conflict or document capacity reached");
    const dependency = insert ? op.predecessorId : op.targetId;
    const missing = dependency !== null && !this.nodes.has(key(dependency));
    if (missing && this.pendingCount >= this.limits.pending)
      throw Error("Pending dependency capacity reached");
    this.seen.set(key(op.operationId), {
      operationId: op.operationId,
      signature: sig,
    });
    if (insert) this.owners.add(key(op.elementId));
    this.maxLamport = Math.max(this.maxLamport, op.lamport);
    if (missing) {
      const list = this.waiting.get(key(dependency)) ?? [];
      list.push(op);
      this.waiting.set(key(dependency), list);
      this.pendingCount++;
      return "PENDING";
    }
    this.integrate(op);
    return "APPLIED";
  }
  private integrate(first: Operation): void {
    const queue = [first];
    for (let index = 0; index < queue.length; index++) {
      const op = queue[index];
      if (op.type === "DELETE")
        this.nodes.get(key(op.targetId))!.deleted = true;
      else {
        const node: Node = {
          id: op.elementId!,
          predecessor: op.predecessorId,
          value: op.value!,
          deleted: false,
          insertOperationId: op.operationId,
          children: [],
        };
        this.nodes.set(key(node.id), node);
        this.addChild(
          node.predecessor === null
            ? this.roots
            : this.nodes.get(key(node.predecessor))!.children,
          node.id,
        );
        const unlocked = this.waiting.get(key(node.id));
        if (unlocked) {
          this.waiting.delete(key(node.id));
          this.pendingCount -= unlocked.length;
          queue.push(...unlocked);
        }
      }
    }
  }
  private addChild(children: Id[], id: Id): void {
    let low = 0,
      high = children.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (compare(children[middle], id) > 0) low = middle + 1;
      else high = middle;
    }
    children.splice(low, 0, id); // Binary search, O(s) array movement; Java uses a TreeSet.
  }
  visible(): NodeState[] {
    const result: NodeState[] = [],
      stack: { children: Id[]; index: number }[] = [
        { children: this.roots, index: 0 },
      ];
    while (stack.length) {
      const top = stack[stack.length - 1];
      if (top.index === top.children.length) {
        stack.pop();
        continue;
      }
      const node = this.nodes.get(key(top.children[top.index++]))!;
      if (!node.deleted)
        result.push({
          id: node.id,
          predecessor: node.predecessor,
          value: node.value,
          deleted: node.deleted,
          insertOperationId: node.insertOperationId,
        });
      if (node.children.length)
        stack.push({ children: node.children, index: 0 });
    }
    return result;
  }
  text(): string {
    return this.visible()
      .map((n) => n.value)
      .join("");
  }
  snapshot(): State {
    return structuredClone({
      nodes: [...this.nodes.values()]
        .map(({ children: _children, ...node }) => node)
        .sort((a, b) => compare(a.id, b.id)),
      pending: [...this.waiting.values()]
        .flat()
        .sort((a, b) => compare(a.operationId, b.operationId)),
      seen: [...this.seen.values()].sort((a, b) =>
        compare(a.operationId, b.operationId),
      ),
      maxLamport: this.maxLamport,
    });
  }
  static restore(state: State): SequenceCrdt {
    const r = new SequenceCrdt();
    if (
      state.nodes.length > 5000 ||
      state.pending.length > 256 ||
      state.seen.length > 10000 ||
      !Number.isSafeInteger(state.maxLamport) ||
      state.maxLamport < 0
    )
      throw Error("Invalid snapshot bounds");
    for (const item of state.seen) {
      if (!validId(item.operationId) || r.seen.has(key(item.operationId)))
        throw Error("Invalid snapshot identity");
      r.seen.set(key(item.operationId), structuredClone(item));
    }
    for (const node of state.nodes) {
      const op: Operation = {
        type: "INSERT",
        operationId: node.insertOperationId,
        elementId: node.id,
        lamport: node.id.counter,
        predecessorId: node.predecessor,
        value: node.value,
        targetId: null,
      };
      validate(op);
      if (
        node.id.counter > state.maxLamport ||
        r.nodes.has(key(node.id)) ||
        r.seen.get(key(node.insertOperationId))?.signature !== signature(op)
      )
        throw Error("Invalid snapshot node");
      r.nodes.set(key(node.id), { ...structuredClone(node), children: [] });
      r.owners.add(key(node.id));
    }
    for (const node of r.nodes.values()) {
      const children =
        node.predecessor === null
          ? r.roots
          : r.nodes.get(key(node.predecessor))?.children;
      if (!children) throw Error("Missing snapshot parent");
      r.addChild(children, node.id);
    }
    const pendingIds = new Set<string>();
    for (const source of state.pending) {
      const op = structuredClone(source);
      validate(op);
      const dependency = op.type === "INSERT" ? op.predecessorId : op.targetId;
      if (
        dependency === null ||
        r.nodes.has(key(dependency)) ||
        pendingIds.has(key(op.operationId)) ||
        op.lamport > state.maxLamport ||
        r.seen.get(key(op.operationId))?.signature !== signature(op)
      )
        throw Error("Invalid pending snapshot operation");
      pendingIds.add(key(op.operationId));
      if (op.type === "INSERT") {
        if (r.owners.has(key(op.elementId)))
          throw Error("Conflicting snapshot element");
        r.owners.add(key(op.elementId));
      }
      const list = r.waiting.get(key(dependency)) ?? [];
      list.push(op);
      r.waiting.set(key(dependency), list);
      r.pendingCount++;
    }
    if (r.owners.size > 5000) throw Error("Invalid snapshot capacity");
    r.maxLamport = state.maxLamport;
    return r;
  }
}

/** Produce immutable operations from a Unicode-scalar diff of a textarea change. */
export function editOperations(
  replica: SequenceCrdt,
  value: string,
  actor: string,
  sequence: number,
  clock: number,
): Operation[] {
  const before = replica.visible(),
    after = Array.from(value);
  let prefix = 0,
    suffix = 0;
  while (
    prefix < before.length &&
    prefix < after.length &&
    before[prefix].value === after[prefix]
  )
    prefix++;
  while (
    suffix < before.length - prefix &&
    suffix < after.length - prefix &&
    before[before.length - 1 - suffix].value ===
      after[after.length - 1 - suffix]
  )
    suffix++;
  const removed = before.length - prefix - suffix,
    inserted = after.length - prefix - suffix;
  if (
    inserted + removed > 1000 ||
    replica.nodeCount + inserted > 5000 ||
    replica.operationCount + inserted + removed > 10000
  )
    throw Error(
      "Edit exceeds document limits. Use smaller edits or create a new document.",
    );
  const operations: Operation[] = [];
  let lamport = Math.max(clock, replica.maxLamport);
  for (let i = prefix; i < before.length - suffix; i++)
    operations.push({
      type: "DELETE",
      operationId: { actor, counter: ++sequence },
      lamport: ++lamport,
      elementId: null,
      predecessorId: null,
      value: null,
      targetId: before[i].id,
    });
  let predecessor = prefix ? before[prefix - 1].id : null;
  for (let i = prefix; i < after.length - suffix; i++) {
    const id = { actor, counter: ++lamport };
    operations.push({
      type: "INSERT",
      operationId: { actor, counter: ++sequence },
      lamport,
      elementId: id,
      predecessorId: predecessor,
      value: after[i],
      targetId: null,
    });
    predecessor = id;
  }
  operations.forEach(validate);
  return operations;
}
