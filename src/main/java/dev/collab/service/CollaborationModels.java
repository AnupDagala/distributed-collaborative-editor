package dev.collab.service;

import dev.collab.crdt.*;
import java.time.Instant;
import java.util.*;

/** Immutable domain and wire DTOs shared across application boundaries. */
public final class CollaborationModels {
  private CollaborationModels() {}

  public enum Role {
    OWNER,
    EDITOR,
    VIEWER
  }

  public record DocumentView(UUID id, String title, Role role, long sequence, Instant updatedAt) {}

  public record Member(UUID userId, String email, Role role) {}

  public record LogEntry(long serverSequence, Operation operation, Instant createdAt) {}

  public record Snapshot(long watermark, SequenceCrdt.State state) {}

  public record Page(
      List<LogEntry> operations,
      long throughSequence,
      long documentSequence,
      boolean hasMore,
      Role role) {}

  public record Connection(
      UUID userId, UUID documentId, UUID actorId, long generation, Instant expiresAt) {}

  public record Ticket(
      String ticket,
      UUID actorId,
      long nextSequence,
      long maxLamport,
      Instant expiresAt,
      String socketPath) {}

  public record Ack(Id operationId, long serverSequence, boolean duplicate) {}

  public record Changed(
      UUID documentId, long sequence, int accepted, int duplicates, int snapshots) {}
}
