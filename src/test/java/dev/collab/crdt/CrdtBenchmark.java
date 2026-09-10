package dev.collab.crdt;

import java.util.*;

/**
 * Dependency-free, reproducible wall-clock microbenchmark; not a substitute for JMH or load tests.
 */
public final class CrdtBenchmark {
  private static volatile int sink;

  public static void main(String[] args) {
    System.out.println("elements,apply_median_ms,render_median_ms,snapshot_restore_median_ms");
    for (int count : new int[] {1000, 10000}) {
      List<Operation> ops = new ArrayList<>();
      UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000001");
      Id previous = null;
      for (int i = 1; i <= count; i++) {
        var op = Operation.insert(new Id(actor, i), i, previous, "x");
        ops.add(op);
        previous = op.elementId();
      }
      long[] apply = new long[15], render = new long[15], restore = new long[15];
      for (int run = -5; run < 15; run++) {
        long start = System.nanoTime();
        var replica = new SequenceCrdt(new SequenceCrdt.Limits(20000, 40000, 20000));
        ops.forEach(replica::apply);
        long applied = System.nanoTime();
        sink = replica.text().length();
        long rendered = System.nanoTime();
        var state = replica.snapshot();
        long snap = System.nanoTime();
        sink =
            SequenceCrdt.restore(state, new SequenceCrdt.Limits(20000, 40000, 20000))
                .operationCount();
        long restored = System.nanoTime();
        if (run >= 0) {
          apply[run] = applied - start;
          render[run] = rendered - applied;
          restore[run] = restored - snap;
        }
      }
      Arrays.sort(apply);
      Arrays.sort(render);
      Arrays.sort(restore);
      System.out.printf(
          Locale.ROOT,
          "%d,%.3f,%.3f,%.3f%n",
          count,
          apply[7] / 1e6,
          render[7] / 1e6,
          restore[7] / 1e6);
    }
  }
}
