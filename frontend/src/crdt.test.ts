import { describe, expect, it } from "vitest";
import { SequenceCrdt, editOperations } from "./crdt";
import type { Operation, Id } from "./crdt";
import { applyPage, recover } from "./sync";
const A = "00000000-0000-0000-0000-000000000001",
  B = "ffffffff-ffff-ffff-ffff-ffffffffffff";
const insert = (
  actor: string,
  seq: number,
  clock: number,
  predecessor: Id | null,
  value: string,
): Operation => ({
  type: "INSERT",
  operationId: { actor, counter: seq },
  lamport: clock,
  elementId: { actor, counter: clock },
  predecessorId: predecessor,
  value,
  targetId: null,
});
function rng(seed: number) {
  return () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed / 4294967296;
  };
}
function shuffle<T>(items: T[], random: () => number) {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}
describe("sequence CRDT", () => {
  it("deterministically resolves concurrent inserts and tombstoned parents", () => {
    const root = insert(A, 1, 1, null, "H"),
      x = insert(A, 2, 2, root.elementId, "X"),
      y = insert(B, 1, 2, root.elementId, "Y");
    const deletion: Operation = {
      type: "DELETE",
      operationId: { actor: B, counter: 2 },
      lamport: 3,
      targetId: root.elementId,
      elementId: null,
      predecessorId: null,
      value: null,
    };
    for (let seed = 0; seed < 100; seed++) {
      const r = new SequenceCrdt();
      shuffle([root, x, y, deletion, x], rng(seed)).forEach((op) =>
        r.apply(op),
      );
      expect(r.text()).toBe("YX");
      expect(r.pendingCount).toBe(0);
      expect(SequenceCrdt.restore(r.snapshot()).snapshot()).toEqual(
        r.snapshot(),
      );
    }
  });
  it("merges independent edits across three replicas under reordered duplicate delivery", () => {
    for (let seed = 0; seed < 30; seed++) {
      const random = rng(seed),
        replicas = [new SequenceCrdt(), new SequenceCrdt(), new SequenceCrdt()],
        sequences = [0, 0, 0],
        actors = [A, B, "00000000-0000-0000-0000-000000000003"],
        all: Operation[] = [];
      for (let i = 0; i < 100; i++) {
        const who = Math.floor(random() * 3),
          replica = replicas[who];
        const before = Array.from(replica.text()),
          pos = Math.floor(random() * (before.length + 1));
        if (random() < 0.25 && before.length)
          before.splice(Math.min(pos, before.length - 1), 1);
        else before.splice(pos, 0, i % 13 === 0 ? "😀" : "x");
        const ops = editOperations(
          replica,
          before.join(""),
          actors[who],
          sequences[who],
          replica.maxLamport,
        );
        ops.forEach((op) => replica.apply(op));
        if (ops.length)
          sequences[who] = ops[ops.length - 1].operationId.counter;
        all.push(...ops);
        if (random() < 0.5)
          ops.forEach((op) => replicas[Math.floor(random() * 3)].apply(op));
      }
      for (const replica of replicas)
        shuffle([...all, ...all.slice(0, 25)], random).forEach((op) =>
          replica.apply(op),
        );
      expect(replicas[0].snapshot()).toEqual(replicas[1].snapshot());
      expect(replicas[1].snapshot()).toEqual(replicas[2].snapshot());
      expect(replicas[0].pendingCount).toBe(0);
    }
  });
  it("rejects invalid Unicode, cycles, conflicting operation IDs and element IDs", () => {
    const r = new SequenceCrdt();
    const op = insert(A, 1, 1, null, "🌍");
    r.apply(op);
    expect(() => r.apply({ ...op, value: "x" })).toThrow();
    expect(() => r.apply(insert(A, 2, 1, null, "x"))).toThrow();
    for (const value of ["", "ab", "\ud800"])
      expect(() => r.apply(insert(A, 2, 2, null, value))).toThrow();
    expect(() =>
      r.apply(insert(A, 2, 2, { actor: A, counter: 2 }, "x")),
    ).toThrow();
  });
  it("snapshots preserve pending inserts and delete-before-insert", () => {
    const root = insert(A, 1, 1, null, "🌍"),
      child = insert(A, 2, 2, root.elementId, "é");
    const r = new SequenceCrdt();
    r.apply(child);
    r.apply({
      type: "DELETE",
      operationId: { actor: B, counter: 1 },
      lamport: 3,
      targetId: root.elementId,
      elementId: null,
      predecessorId: null,
      value: null,
    });
    const restored = SequenceCrdt.restore(r.snapshot());
    restored.apply(root);
    expect(restored.text()).toBe("é");
    expect(restored.apply(child)).toBe("DUPLICATE");
  });
  it("generates scalar-safe replacement operations", () => {
    const r = new SequenceCrdt();
    const initial = editOperations(r, "a😀é", A, 0, 0);
    initial.forEach((op) => r.apply(op));
    const replacement = editOperations(r, "a🌍é", A, 3, 3);
    replacement.forEach((op) => r.apply(op));
    expect(r.text()).toBe("a🌍é");
  });
  it("buffers a long reversed chain and renders without recursion", () => {
    const ops: Operation[] = [];
    let predecessor: Id | null = null;
    for (let i = 1; i <= 10000; i++) {
      const op = insert(A, i, i, predecessor, "x");
      ops.push(op);
      predecessor = op.elementId;
    }
    const r = new SequenceCrdt({
      nodes: 12000,
      operations: 12000,
      pending: 12000,
    });
    ops.reverse().forEach((op) => r.apply(op));
    expect(r.text().length).toBe(10000);
  });
  it("does not record a capacity rejection as an acknowledged operation", () => {
    const root = insert(A, 1, 1, null, "a"),
      child = insert(A, 2, 2, root.elementId, "b"),
      other = insert(B, 1, 3, root.elementId, "c");
    const r = new SequenceCrdt({ nodes: 10, operations: 20, pending: 1 });
    r.apply(child);
    expect(() => r.apply(other)).toThrow();
    r.apply(root);
    expect(r.apply(other)).toBe("APPLIED");
    expect(r.text()).toBe("acb");
  });
});
describe("recovery protocol", () => {
  it("applies contiguous history including duplicates without skipping a remote edit", () => {
    const r = new SequenceCrdt();
    const local = insert(A, 1, 1, null, "a"),
      remote = insert(B, 1, 1, null, "b");
    r.apply(local);
    const cursor = applyPage(r, 0, {
      operations: [
        { serverSequence: 1, operation: remote },
        { serverSequence: 2, operation: local },
      ],
      throughSequence: 2,
    });
    expect(cursor).toBe(2);
    expect(r.text()).toBe("ba");
  });
  it("rejects gaps and inconsistent cursors", () => {
    const r = new SequenceCrdt();
    const op = insert(A, 1, 1, null, "a");
    expect(() =>
      applyPage(r, 0, {
        operations: [{ serverSequence: 2, operation: op }],
        throughSequence: 2,
      }),
    ).toThrow();
    expect(() =>
      applyPage(r, 0, { operations: [], throughSequence: 1 }),
    ).toThrow();
  });
  it("reapplies unacknowledged edits over a snapshot without duplicating accepted edits", () => {
    const server = new SequenceCrdt();
    const accepted = insert(A, 1, 1, null, "a"),
      unsent = insert(A, 2, 2, accepted.elementId, "b");
    server.apply(accepted);
    const recovered = recover({ watermark: 1, state: server.snapshot() }, [
      accepted,
      unsent,
    ]);
    expect(recovered.text()).toBe("ab");
    expect(recovered.operationCount).toBe(2);
  });
});
