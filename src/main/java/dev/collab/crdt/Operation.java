package dev.collab.crdt;

import java.util.Objects;

/** Flat, immutable wire schema: never uses polymorphic class names supplied by a peer. */
public record Operation(
    Type type,
    Id operationId,
    long lamport,
    Id elementId,
    Id predecessorId,
    String value,
    Id targetId) {
  public enum Type {
    INSERT,
    DELETE
  }

  public Operation {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(operationId, "operationId");
    if (lamport < 1 || lamport > Id.MAX_COUNTER)
      throw new IllegalArgumentException("Invalid clock");
    if (type == Type.INSERT) {
      if (elementId == null
          || !elementId.actor().equals(operationId.actor())
          || elementId.counter() != lamport
          || targetId != null
          || !scalar(value)) throw new IllegalArgumentException("Invalid insert");
      if (predecessorId != null && predecessorId.counter() >= lamport)
        throw new IllegalArgumentException("Predecessor must precede insertion clock");
    } else {
      if (targetId == null
          || elementId != null
          || predecessorId != null
          || value != null
          || targetId.counter() >= lamport) throw new IllegalArgumentException("Invalid delete");
    }
  }

  private static boolean scalar(String value) {
    if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) != 1)
      return false;
    int cp = value.codePointAt(0);
    return cp < 0xd800 || cp > 0xdfff;
  }

  private static String key(Id id) {
    return id == null ? "-" : id.key();
  }

  public String signature() {
    return type
        + "|"
        + operationId.key()
        + "|"
        + lamport
        + "|"
        + key(elementId)
        + "|"
        + key(predecessorId)
        + "|"
        + key(targetId)
        + "|"
        + (value == null ? "" : value);
  }

  public static Operation insert(Id op, long clock, Id predecessor, String value) {
    return new Operation(
        Type.INSERT, op, clock, new Id(op.actor(), clock), predecessor, value, null);
  }

  public static Operation delete(Id op, long clock, Id target) {
    return new Operation(Type.DELETE, op, clock, null, null, null, target);
  }
}
