package dev.collab.crdt;

public final class LamportClock {
  private long value;

  public void observe(long remote) {
    if (remote < 0 || remote > Id.MAX_COUNTER) throw new IllegalArgumentException("Invalid clock");
    value = Math.max(value, remote);
  }

  public long tick() {
    if (value == Id.MAX_COUNTER) throw new IllegalStateException("Clock exhausted");
    return ++value;
  }

  public long value() {
    return value;
  }
}
