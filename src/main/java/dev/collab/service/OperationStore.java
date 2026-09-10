package dev.collab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collab.crdt.*;
import dev.collab.security.Digests;
import dev.collab.service.CollaborationModels.*;
import dev.collab.store.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public OperationStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public List<LogEntry> log(UUID docId, long after, long through, int limit) {
    return db.query(
        "select server_sequence,payload,checksum,created_at from operations where document_id=? and"
            + " server_sequence>? and server_sequence<=? order by server_sequence limit ?",
        (rs, row) -> {
          Operation op = read(rs.getString(2), Operation.class);
          if (!Digests.sha256(op.signature()).equals(rs.getString(3)))
            throw new IllegalStateException("Operation checksum mismatch");
          return new LogEntry(rs.getLong(1), op, rs.getTimestamp(4).toInstant());
        },
        docId,
        after,
        through,
        limit);
  }

  public SequenceCrdt load(UUID docId, long through) {
    var stored =
        db.query(
            "select watermark,state,checksum from snapshots where document_id=? and watermark<=?"
                + " order by watermark desc limit 1",
            (rs, row) -> {
              String state = rs.getString(2);
              if (!Digests.sha256(state).equals(rs.getString(3)))
                throw new IllegalStateException("Snapshot checksum mismatch");
              return new Snapshot(rs.getLong(1), read(state, SequenceCrdt.State.class));
            },
            docId,
            through);
    long cursor = stored.isEmpty() ? 0 : stored.getFirst().watermark();
    SequenceCrdt replica =
        stored.isEmpty()
            ? new SequenceCrdt()
            : SequenceCrdt.restore(stored.getFirst().state(), SequenceCrdt.Limits.document());
    for (var entry : log(docId, cursor, through, 10000)) {
      if (entry.serverSequence() != ++cursor) throw new IllegalStateException("Operation log gap");
      replica.apply(entry.operation());
    }
    if (cursor != through) throw new IllegalStateException("Incomplete operation log");
    return replica;
  }

  public String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Serialization failed");
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored state invalid");
    }
  }
}
