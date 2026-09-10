package dev.collab.service;

import dev.collab.api.ApiException;
import dev.collab.config.EditorProperties;
import dev.collab.crdt.*;
import dev.collab.security.Digests;
import dev.collab.service.CollaborationModels.*;
import dev.collab.store.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollaborationService {
  private record Actor(UUID userId, UUID documentId, long sequence, long clock, long generation) {}

  private final DocumentRepository documents;
  private final DocumentLifecycleService lifecycle;
  private final DocumentAccess access;
  private final OperationStore store;
  private final JdbcTemplate db;
  private final EditorProperties properties;
  private final ApplicationEventPublisher events;

  public CollaborationService(
      DocumentRepository documents,
      DocumentLifecycleService lifecycle,
      DocumentAccess access,
      OperationStore store,
      JdbcTemplate db,
      EditorProperties properties,
      ApplicationEventPublisher events) {
    this.documents = documents;
    this.lifecycle = lifecycle;
    this.access = access;
    this.store = store;
    this.db = db;
    this.properties = properties;
    this.events = events;
  }

  public DocumentView create(UUID userId, String title) {
    return lifecycle.create(userId, title);
  }

  public List<DocumentView> list(UUID userId, int offset, int limit) {
    return lifecycle.list(userId, offset, limit);
  }

  public DocumentView get(UUID docId, UUID userId) {
    return lifecycle.get(docId, userId);
  }

  public void delete(UUID docId, UUID userId) {
    lifecycle.delete(docId, userId);
  }

  public List<Member> members(UUID docId, UUID userId) {
    return lifecycle.members(docId, userId);
  }

  public void share(UUID docId, UUID userId, String email, Role role) {
    lifecycle.share(docId, userId, email, role);
  }

  public void removeMember(UUID docId, UUID userId, UUID memberId) {
    lifecycle.removeMember(docId, userId, memberId);
  }

  @Transactional
  public Ticket ticket(UUID docId, UUID userId, UUID actorId, Instant jwtExpiry) {
    var doc = access.find(docId, true);
    access.role(doc, userId);
    if (jwtExpiry == null || !jwtExpiry.isAfter(Instant.now()))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED");
    Actor actor;
    if (actorId == null) {
      if (db.queryForObject(
              "select count(*) from editing_actors where document_id=?", Long.class, docId)
          >= 1000) throw ApiException.conflict("ACTOR_LIMIT");
      actorId = UUID.randomUUID();
      db.update(
          "insert into editing_actors(id,document_id,user_id) values(?,?,?)",
          actorId,
          docId,
          userId);
      actor = new Actor(userId, docId, 0, 0, 1);
    } else {
      actor = actor(actorId);
      if (!actor.userId().equals(userId) || !actor.documentId().equals(docId))
        throw ApiException.forbidden();
      db.update("update editing_actors set generation=generation+1 where id=?", actorId);
      actor = new Actor(userId, docId, actor.sequence(), actor.clock(), actor.generation() + 1);
    }
    // Only one outstanding credential per actor; old generations cannot submit new edits.
    db.update("delete from websocket_tickets where actor_id=?", actorId);
    String raw = Digests.token();
    Instant expires = Instant.now().plusSeconds(30);
    db.update(
        "insert into"
            + " websocket_tickets(hash,document_id,user_id,actor_id,generation,expires_at,session_expires_at)"
            + " values(?,?,?,?,?,?,?)",
        Digests.sha256(raw),
        docId,
        userId,
        actorId,
        actor.generation(),
        Timestamp.from(expires),
        Timestamp.from(jwtExpiry));
    return new Ticket(
        raw, actorId, actor.sequence() + 1, doc.maxLamport(), jwtExpiry, "/ws/documents/" + docId);
  }

  @Transactional
  public Connection consumeTicket(UUID docId, String raw) {
    // Lock ordering is always document then actor/ticket, including handshake and append.
    var doc = access.find(docId, true);
    var rows =
        db.query(
            "delete from websocket_tickets where hash=? and document_id=? and expires_at>now()"
                + " returning user_id,document_id,actor_id,generation,session_expires_at",
            (rs, row) ->
                new Connection(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getLong(4),
                    rs.getTimestamp(5).toInstant()),
            Digests.sha256(raw),
            docId);
    if (rows.size() != 1) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TICKET");
    var connection = rows.getFirst();
    validate(doc, connection);
    return connection;
  }

  @Transactional
  public List<Ack> append(Connection connection, List<Operation> operations) {
    if (operations == null || operations.isEmpty() || operations.size() > 32)
      throw new IllegalArgumentException("Batch size must be 1..32");
    var doc = access.find(connection.documentId(), true);
    Role role = validate(doc, connection);
    if (role == Role.VIEWER) throw ApiException.forbidden();
    Actor actor = actor(connection.actorId());
    long actorSequence = actor.sequence(), actorClock = actor.clock();
    SequenceCrdt replica = store.load(doc.id(), doc.lastSequence());
    List<Ack> acks = new ArrayList<>();
    int accepted = 0, duplicates = 0, snapshots = 0;
    for (Operation op : operations) {
      if (op == null || !op.operationId().actor().equals(connection.actorId()))
        throw ApiException.forbidden();
      String checksum = Digests.sha256(op.signature());
      var duplicate =
          db.query(
              "select server_sequence,checksum from operations where document_id=? and actor_id=?"
                  + " and operation_sequence=?",
              (rs, row) -> Map.entry(rs.getLong(1), rs.getString(2)),
              doc.id(),
              connection.actorId(),
              op.operationId().counter());
      if (!duplicate.isEmpty()) {
        if (!duplicate.getFirst().getValue().equals(checksum))
          throw ApiException.conflict("OPERATION_ID_CONFLICT");
        duplicates++;
        acks.add(new Ack(op.operationId(), duplicate.getFirst().getKey(), true));
        continue;
      }
      if (op.operationId().counter() != actorSequence + 1)
        throw ApiException.conflict("ACTOR_SEQUENCE_GAP");
      if (op.lamport() <= actorClock || op.lamport() > doc.maxLamport() + 4096)
        throw ApiException.conflict("INVALID_LOGICAL_CLOCK");
      try {
        replica.apply(op);
      } catch (IllegalStateException ex) {
        throw ApiException.conflict("DOCUMENT_CAPACITY");
      }
      long sequence = doc.append(op.lamport(), replica.pendingCount());
      db.update(
          "insert into"
              + " operations(document_id,actor_id,operation_sequence,server_sequence,payload,checksum)"
              + " values(?,?,?,?,?,?)",
          doc.id(),
          connection.actorId(),
          op.operationId().counter(),
          sequence,
          store.write(op),
          checksum);
      actorSequence = op.operationId().counter();
      actorClock = op.lamport();
      accepted++;
      acks.add(new Ack(op.operationId(), sequence, false));
      if (sequence % properties.snapshotInterval() == 0) {
        String state = store.write(replica.snapshot());
        db.update(
            "insert into snapshots(document_id,watermark,state,checksum) values(?,?,?,?)",
            doc.id(),
            sequence,
            state,
            Digests.sha256(state));
        snapshots++;
      }
    }
    db.update(
        "update editing_actors set last_sequence=?,last_lamport=? where id=?",
        actorSequence,
        actorClock,
        connection.actorId());
    // Retain three materialized checkpoints, but never delete authoritative operations or
    // tombstones.
    if (snapshots > 0)
      db.update(
          "delete from snapshots where document_id=? and watermark not in (select watermark from"
              + " snapshots where document_id=? order by watermark desc limit 3)",
          doc.id(),
          doc.id());
    documents.flush();
    events.publishEvent(new Changed(doc.id(), doc.lastSequence(), accepted, duplicates, snapshots));
    return List.copyOf(acks);
  }

  @Transactional(readOnly = true)
  public Snapshot snapshot(UUID docId, UUID userId) {
    var doc = access.find(docId, false);
    access.role(doc, userId);
    return new Snapshot(doc.lastSequence(), store.load(docId, doc.lastSequence()).snapshot());
  }

  @Transactional(readOnly = true)
  public Page history(UUID docId, UUID userId, long after, int limit) {
    var doc = access.find(docId, false);
    return page(doc, access.role(doc, userId), after, limit);
  }

  @Transactional(readOnly = true)
  public Page catchUp(Connection connection, long after) {
    var doc = access.find(connection.documentId(), false);
    return page(doc, validate(doc, connection), after, 100);
  }

  @Transactional(readOnly = true)
  public Role validate(Connection connection) {
    return validate(access.find(connection.documentId(), false), connection);
  }

  @Transactional(readOnly = true)
  public long snapshotWatermark(UUID docId) {
    return db.queryForObject(
        "select coalesce(max(watermark),0) from snapshots where document_id=?", Long.class, docId);
  }

  @Transactional
  public void expireTickets() {
    db.update("delete from websocket_tickets where expires_at<now()");
  }

  @Transactional(readOnly = true)
  public long pendingOperations() {
    return db.queryForObject(
        "select coalesce(sum(pending_count),0) from documents where not deleted", Long.class);
  }

  private Page page(DocumentEntity doc, Role role, long after, int limit) {
    if (after < 0 || after > doc.lastSequence() || limit < 1 || limit > 200)
      throw new IllegalArgumentException("Invalid replay cursor or limit");
    var entries = store.log(doc.id(), after, doc.lastSequence(), limit);
    long through = entries.isEmpty() ? after : entries.getLast().serverSequence();
    return new Page(entries, through, doc.lastSequence(), through < doc.lastSequence(), role);
  }

  private Role validate(DocumentEntity doc, Connection connection) {
    if (!connection.expiresAt().isAfter(Instant.now()))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED");
    Actor actor = actor(connection.actorId());
    if (!doc.id().equals(connection.documentId())
        || !actor.documentId().equals(doc.id())
        || !actor.userId().equals(connection.userId())
        || actor.generation() != connection.generation()) throw ApiException.forbidden();
    return access.role(doc, connection.userId());
  }

  private Actor actor(UUID id) {
    var rows =
        db.query(
            "select user_id,document_id,last_sequence,last_lamport,generation from editing_actors"
                + " where id=?",
            (rs, row) ->
                new Actor(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5)),
            id);
    if (rows.isEmpty()) throw ApiException.forbidden();
    return rows.getFirst();
  }
}
