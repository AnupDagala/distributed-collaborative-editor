package dev.collab.crdt;

import java.util.Objects;
import java.util.UUID;

/** Canonical UUID ordering deliberately matches the browser's string comparator. */
public record Id(UUID actor, long counter) implements Comparable<Id> {
  public static final long MAX_COUNTER = 9_007_199_254_740_991L;

  public Id {
    Objects.requireNonNull(actor, "actor");
    if (counter < 1 || counter > MAX_COUNTER) throw new IllegalArgumentException("Invalid counter");
  }

  @Override
  public int compareTo(Id other) {
    int byCounter = Long.compare(counter, other.counter);
    return byCounter != 0 ? byCounter : actor.toString().compareTo(other.actor.toString());
  }

  public String key() {
    return actor + ":" + counter;
  }
}
