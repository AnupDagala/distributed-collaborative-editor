package dev.collab.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collab.crdt.Id;
import dev.collab.service.CollaborationModels.Connection;
import java.time.Duration;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PresenceService {
  public record Cursor(Id anchor, Id focus) {}

  public record Presence(
      String connectionId, UUID userId, UUID actorId, Cursor cursor, long updatedAt) {}

  private final StringRedisTemplate redis;
  private final ObjectMapper json;

  public PresenceService(StringRedisTemplate redis, ObjectMapper json) {
    this.redis = redis;
    this.json = json;
  }

  private String key(UUID doc) {
    return "collab:presence:" + doc;
  }

  public void update(Connection connection, String connectionId, Cursor cursor) {
    try {
      redis
          .opsForHash()
          .put(
              key(connection.documentId()),
              connectionId,
              json.writeValueAsString(
                  new Presence(
                      connectionId,
                      connection.userId(),
                      connection.actorId(),
                      cursor,
                      System.currentTimeMillis())));
      redis.expire(key(connection.documentId()), Duration.ofSeconds(45));
    } catch (Exception ex) {
      throw new IllegalStateException("Presence unavailable");
    }
  }

  public List<Presence> list(UUID doc) {
    try {
      List<Presence> result = new ArrayList<>();
      long cutoff = System.currentTimeMillis() - 30000;
      for (var entry : redis.opsForHash().entries(key(doc)).entrySet()) {
        Presence item = json.readValue(entry.getValue().toString(), Presence.class);
        if (item.updatedAt() >= cutoff) result.add(item);
        else redis.opsForHash().delete(key(doc), entry.getKey());
      }
      return result.stream().sorted(Comparator.comparing(Presence::connectionId)).toList();
    } catch (Exception ex) {
      throw new IllegalStateException("Presence unavailable");
    }
  }

  public void remove(Connection connection, String id) {
    try {
      redis.opsForHash().delete(key(connection.documentId()), id);
    } catch (RuntimeException ignored) {
      /* TTL removes abandoned presence after an outage. */
    }
  }
}
