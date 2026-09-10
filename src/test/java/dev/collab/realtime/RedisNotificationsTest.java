package dev.collab.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collab.config.EditorProperties;
import dev.collab.service.CollaborationModels.Changed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisNotificationsTest {
  private EditorProperties properties() {
    return new EditorProperties(
        "unused", "issuer", "audience", List.of("http://localhost:5173"), 50, "a");
  }

  @Test
  void failedNotificationDoesNotConvertCommittedEditIntoFailure() {
    var redis = mock(StringRedisTemplate.class);
    var gateway = mock(CollaborationGateway.class);
    var metrics = new SimpleMeterRegistry();
    when(redis.convertAndSend(anyString(), anyString()))
        .thenThrow(new IllegalStateException("unavailable"));
    var notifications =
        new RedisNotifications(redis, new ObjectMapper(), properties(), gateway, metrics);
    UUID doc = UUID.randomUUID();
    assertDoesNotThrow(() -> notifications.committed(new Changed(doc, 1, 1, 0, 0)));
    verify(gateway).wake(doc);
    assertEquals(1, metrics.get("collab.notifications.failed").counter().count());
  }

  @Test
  void notificationRecipientsNeverRepublishAndIgnoreOwnSource() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    var gateway = mock(CollaborationGateway.class);
    var json = new ObjectMapper();
    var notifications =
        new RedisNotifications(redis, json, properties(), gateway, new SimpleMeterRegistry());
    UUID doc = UUID.randomUUID();
    notifications.receive(json.writeValueAsBytes(new RedisNotifications.Notice("a", doc, 1)));
    verifyNoInteractions(gateway);
    byte[] other = json.writeValueAsBytes(new RedisNotifications.Notice("b", doc, 1));
    notifications.receive(other);
    notifications.receive(other);
    verify(gateway, times(2)).wake(doc);
    verifyNoInteractions(redis);
  }
}
