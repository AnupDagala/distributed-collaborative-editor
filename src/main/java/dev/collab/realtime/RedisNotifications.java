package dev.collab.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collab.config.EditorProperties;
import dev.collab.service.CollaborationModels.Changed;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.transaction.event.*;

@Configuration
public class RedisNotifications {
  public record Notice(String sourceInstance, UUID documentId, long serverSequence) {}

  private static final String CHANNEL = "collab:committed:v1";
  private static final Logger log = LoggerFactory.getLogger(RedisNotifications.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final EditorProperties properties;
  private final CollaborationGateway gateway;
  private final MeterRegistry metrics;

  public RedisNotifications(
      StringRedisTemplate redis,
      ObjectMapper json,
      EditorProperties properties,
      CollaborationGateway gateway,
      MeterRegistry metrics) {
    this.redis = redis;
    this.json = json;
    this.properties = properties;
    this.gateway = gateway;
    this.metrics = metrics;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void committed(Changed event) {
    metrics.counter("collab.operations", "outcome", "accepted").increment(event.accepted());
    metrics.counter("collab.operations", "outcome", "duplicate").increment(event.duplicates());
    metrics.counter("collab.snapshots.created").increment(event.snapshots());
    gateway.wake(event.documentId());
    try {
      redis.convertAndSend(
          CHANNEL,
          json.writeValueAsString(
              new Notice(properties.instanceId(), event.documentId(), event.sequence())));
    } catch (Exception ex) {
      metrics.counter("collab.notifications.failed").increment();
      log.warn(
          "Commit notification unavailable; database repair will catch up exceptionType={}",
          ex.getClass().getSimpleName());
    }
  }

  @Bean
  RedisMessageListenerContainer notificationsContainer(RedisConnectionFactory factory) {
    var container = new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    container.setRecoveryInterval(1000L);
    container.setErrorHandler(
        ex ->
            log.warn("Redis listener unavailable exceptionType={}", ex.getClass().getSimpleName()));
    container.addMessageListener(
        (message, pattern) -> receive(message.getBody()), new ChannelTopic(CHANNEL));
    return container;
  }

  void receive(byte[] message) {
    try {
      if (message.length > 1024) return;
      var notice = json.readValue(new String(message, StandardCharsets.UTF_8), Notice.class);
      if (!properties.instanceId().equals(notice.sourceInstance()))
        gateway.wake(notice.documentId());
    } catch (Exception ex) {
      metrics.counter("collab.notifications.invalid").increment();
    }
  }
}
