package dev.collab.crdt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SequenceCrdtTest {
  static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  static final UUID B = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

  static Operation ins(UUID actor, long seq, long clock, Id pred, String text) {
    return Operation.insert(new Id(actor, seq), clock, pred, text);
  }

  static SequenceCrdt replica(List<Operation> ops) {
    var r = new SequenceCrdt(new SequenceCrdt.Limits(20000, 40000, 20000));
    ops.forEach(r::apply);
    return r;
  }

  @Test
  void concurrentSamePositionAndDeletedAncestor() {
    var h = ins(A, 1, 1, null, "H");
    var e = ins(A, 2, 2, h.elementId(), "E");
    var x = ins(A, 3, 3, h.elementId(), "X");
    var y = ins(B, 1, 3, h.elementId(), "Y");
    var del = Operation.delete(new Id(B, 2), 4, h.elementId());
    var ops = List.of(h, e, x, y, del);
    for (int seed = 0; seed < 100; seed++) {
      var shuffled = new ArrayList<>(ops);
      Collections.shuffle(shuffled, new Random(seed));
      assertEquals("YXE", replica(shuffled).text());
      assertEquals(replica(ops).snapshot(), replica(shuffled).snapshot());
    }
  }

  @Test
  void delayedDependenciesAndConcurrentDeletes() {
    var a = ins(A, 1, 1, null, "😀");
    var b = ins(A, 2, 2, a.elementId(), "é");
    var d = Operation.delete(new Id(B, 1), 3, a.elementId());
    var d2 = Operation.delete(new Id(A, 3), 4, a.elementId());
    var r = replica(List.of(d, b, d2));
    assertEquals(3, r.pendingCount());
    assertEquals("", r.text());
    r.apply(a);
    assertEquals("é", r.text());
    assertEquals(0, r.pendingCount());
    assertEquals(SequenceCrdt.Result.DUPLICATE, r.apply(a));
    var restored = SequenceCrdt.restore(r.snapshot(), new SequenceCrdt.Limits(20000, 40000, 20000));
    assertEquals(r.snapshot(), restored.snapshot());
    assertEquals(r.text(), restored.text());
  }

  @Test
  void rejectConflictingIdsAndInvalidScalars() {
    var r = new SequenceCrdt();
    r.apply(ins(A, 1, 1, null, "a"));
    assertThrows(IllegalArgumentException.class, () -> r.apply(ins(A, 1, 1, null, "b")));
    assertThrows(IllegalArgumentException.class, () -> r.apply(ins(A, 2, 1, null, "a")));
    for (String invalid : List.of("", "ab", "\ud800", "\udfff"))
      assertThrows(IllegalArgumentException.class, () -> ins(A, 2, 2, null, invalid));
    assertThrows(IllegalArgumentException.class, () -> ins(A, 2, 2, new Id(A, 2), "x"));
  }

  @Test
  void capacityRejectionIsRetryable() {
    var r = new SequenceCrdt(new SequenceCrdt.Limits(10, 20, 1));
    var root = ins(A, 1, 1, null, "a");
    var child = ins(A, 2, 2, root.elementId(), "b");
    var child2 = ins(B, 1, 3, root.elementId(), "c");
    r.apply(child);
    assertThrows(IllegalStateException.class, () -> r.apply(child2));
    assertEquals(1, r.operationCount());
    r.apply(root);
    r.apply(child2);
    assertEquals("acb", r.text());
  }

  @Test
  void snapshotPreservesPendingAndDeduplication() {
    var root = ins(A, 1, 1, null, "a");
    var child = ins(A, 2, 2, root.elementId(), "b");
    var r = replica(List.of(child));
    var s = SequenceCrdt.restore(r.snapshot(), SequenceCrdt.Limits.document());
    assertEquals(SequenceCrdt.Result.DUPLICATE, s.apply(child));
    s.apply(root);
    assertEquals("ab", s.text());
  }

  @Test
  void deepChainDoesNotOverflowStack() {
    var r = new SequenceCrdt(new SequenceCrdt.Limits(15000, 20000, 15000));
    Id predecessor = null;
    List<Operation> ops = new ArrayList<>();
    for (int i = 1; i <= 10000; i++) {
      var op = ins(A, i, i, predecessor, "x");
      ops.add(op);
      predecessor = op.elementId();
    }
    Collections.reverse(ops);
    ops.forEach(r::apply);
    assertEquals(10000, r.text().length());
    assertEquals(0, r.pendingCount());
    assertEquals(
        r.text(),
        SequenceCrdt.restore(r.snapshot(), new SequenceCrdt.Limits(15000, 20000, 15000)).text());
  }

  @Test
  void independentConcurrentReplicasConvergeAcrossSeededSchedules() {
    for (int seed = 0; seed < 40; seed++) {
      Random random = new Random(seed);
      List<Operation> operations = new ArrayList<>();
      var editors = List.of(replica(List.of()), replica(List.of()), replica(List.of()));
      UUID[] actors = {A, B, UUID.fromString("00000000-0000-0000-0000-000000000003")};
      long[] seq = new long[3];
      for (int i = 0; i < 300; i++) {
        int who = random.nextInt(3);
        var editor = editors.get(who);
        var ids = editor.visibleIds();
        long clock = editor.maxLamport() + 1;
        Operation op;
        if (!ids.isEmpty() && random.nextInt(4) == 0)
          op =
              Operation.delete(
                  new Id(actors[who], ++seq[who]), clock, ids.get(random.nextInt(ids.size())));
        else {
          int pos = random.nextInt(ids.size() + 1);
          op =
              ins(
                  actors[who],
                  ++seq[who],
                  clock,
                  pos == 0 ? null : ids.get(pos - 1),
                  i % 17 == 0 ? "🌍" : "x");
        }
        editor.apply(op);
        operations.add(op);
        if (random.nextBoolean()) editors.get(random.nextInt(3)).apply(op);
      }
      var expected = replica(operations);
      for (var editor : editors) {
        List<Operation> delivered = new ArrayList<>(operations);
        delivered.addAll(operations.subList(0, 100));
        Collections.shuffle(delivered, random);
        delivered.forEach(editor::apply);
        assertEquals(0, editor.pendingCount(), "seed=" + seed);
        assertEquals(expected.snapshot(), editor.snapshot(), "seed=" + seed);
        assertEquals(expected.text(), editor.text());
        assertEquals(expected.visibleIds(), editor.visibleIds());
      }
    }
  }

  @Test
  void logicalClockIsMonotonicAndBounded() {
    var clock = new LamportClock();
    assertEquals(1, clock.tick());
    clock.observe(50);
    clock.observe(2);
    assertEquals(51, clock.tick());
    clock.observe(Id.MAX_COUNTER);
    assertThrows(IllegalStateException.class, clock::tick);
  }
}
