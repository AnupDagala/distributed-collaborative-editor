package dev.collab.store;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentEntity {
  @Id private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 160)
  private String title;

  @Column(name = "last_sequence", nullable = false)
  private long lastSequence;

  @Column(name = "max_lamport", nullable = false)
  private long maxLamport;

  @Column(name = "pending_count", nullable = false)
  private int pendingCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(nullable = false)
  private boolean deleted;

  @Version private long version;

  protected DocumentEntity() {}

  public DocumentEntity(UUID id, UUID ownerId, String title) {
    this.id = id;
    this.ownerId = ownerId;
    this.title = title;
    createdAt = updatedAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public String title() {
    return title;
  }

  public long lastSequence() {
    return lastSequence;
  }

  public long maxLamport() {
    return maxLamport;
  }

  public boolean deleted() {
    return deleted;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public long append(long clock, int pending) {
    lastSequence++;
    maxLamport = Math.max(maxLamport, clock);
    pendingCount = pending;
    updatedAt = Instant.now();
    return lastSequence;
  }

  public void delete() {
    deleted = true;
    updatedAt = Instant.now();
  }
}
