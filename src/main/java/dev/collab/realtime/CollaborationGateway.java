package dev.collab.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collab.api.ApiException;
import dev.collab.crdt.Operation;
import dev.collab.realtime.PresenceService.Cursor;
import dev.collab.security.RateLimiter;
import dev.collab.service.CollaborationModels.*;
import dev.collab.service.CollaborationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Component
public class CollaborationGateway extends TextWebSocketHandler implements SubProtocolCapable {
  public record Incoming(
      int protocolVersion,
      String type,
      UUID documentId,
      UUID actorId,
      Long afterSequence,
      List<Operation> operations,
      Cursor presence) {}

  private static final Logger log = LoggerFactory.getLogger(CollaborationGateway.class);

  private static final class Client {
    final WebSocketSession session;
    final Connection connection;
    final String id = UUID.randomUUID().toString();
    boolean joined, needsSnapshot;
    long after;
    long lastPresence;

    Client(WebSocketSession raw, Connection connection) {
      this.session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 1024 * 1024);
      this.connection = connection;
    }
  }

  private final Map<String, Client> clients = new ConcurrentHashMap<>();
  private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
  private final AtomicLong pendingGauge = new AtomicLong();
  private final CollaborationService service;
  private final PresenceService presence;
  private final RateLimiter limiter;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private long lastFullPoll;

  public CollaborationGateway(
      CollaborationService service,
      PresenceService presence,
      RateLimiter limiter,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.service = service;
    this.presence = presence;
    this.limiter = limiter;
    this.json = json;
    this.metrics = metrics;
    metrics.gauge("collab.connections.active", clients, Map::size);
    metrics.gauge("collab.operations.pending", pendingGauge);
  }

  @Override
  public List<String> getSubProtocols() {
    return List.of("collab.v1");
  }

  @Override
  public synchronized void afterConnectionEstablished(WebSocketSession raw) throws Exception {
    Connection connection = (Connection) raw.getAttributes().get("connection");
    if (connection == null
        || clients.size() >= 128
        || clients.values().stream()
                .filter(c -> c.connection.documentId().equals(connection.documentId()))
                .count()
            >= 64) {
      raw.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    // Tomcat also uses its binary buffer as UTF-8 decoding scratch space for text.
    // TextWebSocketHandler rejects binary messages; a zero-byte buffer breaks emoji frames.
    raw.setTextMessageSizeLimit(32768);
    raw.setBinaryMessageSizeLimit(32768);
    clients.put(raw.getId(), new Client(raw, connection));
    metrics.counter("collab.websocket.connections", "outcome", "opened").increment();
  }

  @Override
  protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
    Client client = clients.get(raw.getId());
    if (client == null) return;
    synchronized (client) {
      try {
        if (message.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768) {
          error(client, "MESSAGE_TOO_LARGE");
          close(client, CloseStatus.TOO_BIG_TO_PROCESS);
          return;
        }
        Incoming incoming = json.readValue(message.getPayload(), Incoming.class);
        if (incoming.protocolVersion() != 1
            || !client.connection.documentId().equals(incoming.documentId())
            || !client.connection.actorId().equals(incoming.actorId())
            || incoming.type() == null) throw new IllegalArgumentException("Invalid envelope");
        limiter.require("ws", client.connection.userId().toString(), 1, 240, 1000);
        log.debug(
            "Received message connectionId={} type={}",
            client.id,
            Set.of("JOIN_DOCUMENT", "SYNC_REQUEST", "EDIT", "PRESENCE", "LEAVE_DOCUMENT")
                    .contains(incoming.type())
                ? incoming.type()
                : "UNKNOWN");
        switch (incoming.type()) {
          case "JOIN_DOCUMENT", "SYNC_REQUEST" -> {
            if (incoming.afterSequence() == null
                || incoming.operations() != null
                || incoming.presence() != null) throw new IllegalArgumentException("Invalid sync");
            client.after = incoming.afterSequence();
            client.joined = true;
            client.needsSnapshot = false;
            pump(client);
          }
          case "EDIT" -> {
            if (!client.joined
                || incoming.afterSequence() != null
                || incoming.presence() != null
                || incoming.operations() == null)
              throw new IllegalArgumentException("Invalid edit");
            if (client.needsSnapshot) {
              Role role = service.validate(client.connection);
              send(
                  client,
                  "SYNC_REQUIRED",
                  Map.of(
                      "snapshotWatermark",
                      service.snapshotWatermark(client.connection.documentId()),
                      "role",
                      role));
              break;
            }
            limiter.require(
                "edit",
                client.connection.userId().toString(),
                incoming.operations().size(),
                240,
                1000);
            var acks = service.append(client.connection, incoming.operations());
            send(client, "ACK", Map.of("acknowledgments", acks));
            pump(client);
          }
          case "PRESENCE" -> {
            if (!client.joined
                || incoming.afterSequence() != null
                || incoming.operations() != null
                || incoming.presence() == null)
              throw new IllegalArgumentException("Invalid presence");
            service.validate(client.connection);
            limiter.require("presence", client.connection.userId().toString(), 1, 20, 1000);
            presence.update(client.connection, client.id, incoming.presence());
            dirty.add(client.connection.documentId());
          }
          case "LEAVE_DOCUMENT" -> {
            if (incoming.afterSequence() != null
                || incoming.operations() != null
                || incoming.presence() != null) throw new IllegalArgumentException("Invalid leave");
            close(client, CloseStatus.NORMAL);
          }
          default -> throw new IllegalArgumentException("Unknown message");
        }
        metrics.counter("collab.websocket.messages", "direction", "in").increment();
      } catch (ApiException ex) {
        error(client, ex.code());
        if (ex.status().value() != 429 && ex.status().value() != 503)
          close(client, CloseStatus.POLICY_VIOLATION);
      } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
        error(client, "INVALID_MESSAGE");
        close(client, CloseStatus.BAD_DATA);
      } catch (Exception ex) {
        log.warn(
            "WebSocket request failed connectionId={} exceptionType={}",
            client.id,
            ex.getClass().getSimpleName());
        error(client, "SERVICE_UNAVAILABLE");
        close(client, CloseStatus.SERVER_ERROR);
      }
    }
  }

  private void pump(Client client) throws IOException {
    if (!client.joined) return;
    // Also rechecks role, expiry and actor generation on otherwise idle connections.
    Page page = service.catchUp(client.connection, client.after);
    if (client.needsSnapshot) return;
    long checkpoint = service.snapshotWatermark(client.connection.documentId());
    if (client.after < checkpoint && page.documentSequence() - client.after > 100) {
      client.needsSnapshot = true;
      send(client, "SYNC_REQUIRED", Map.of("snapshotWatermark", checkpoint, "role", page.role()));
      return;
    }
    send(client, "SYNC_RESPONSE", page);
    client.after = page.throughSequence();
    if (page.hasMore()) dirty.add(client.connection.documentId());
    if (System.currentTimeMillis() - client.lastPresence >= 1000) {
      try {
        send(
            client,
            "PRESENCE",
            Map.of("users", presence.list(client.connection.documentId()), "available", true));
      } catch (RuntimeException ex) {
        send(client, "PRESENCE", Map.of("users", List.of(), "available", false));
      }
      client.lastPresence = System.currentTimeMillis();
    }
  }

  public void wake(UUID documentId) {
    dirty.add(documentId);
  }

  @Scheduled(fixedDelay = 250)
  public void repairAndBroadcast() {
    long now = System.currentTimeMillis();
    boolean all = now - lastFullPoll >= 1000;
    if (all) lastFullPoll = now;
    Set<UUID> changed = new HashSet<>(dirty);
    dirty.removeAll(changed);
    for (Client client : clients.values())
      if (all || changed.contains(client.connection.documentId()))
        synchronized (client) {
          try {
            if (!client.joined) {
              service.validate(client.connection);
              continue;
            }
            pump(client);
          } catch (ApiException ex) {
            error(client, ex.code());
            close(client, CloseStatus.POLICY_VIOLATION);
          } catch (Exception ex) {
            error(client, "SERVICE_UNAVAILABLE");
            close(client, CloseStatus.SERVER_ERROR);
          }
        }
  }

  @Scheduled(fixedDelay = 30000)
  public void maintain() {
    try {
      service.expireTickets();
      pendingGauge.set(service.pendingOperations());
    } catch (RuntimeException ex) {
      log.warn("Maintenance unavailable exceptionType={}", ex.getClass().getSimpleName());
    }
  }

  private void send(Client client, String type, Object data) throws IOException {
    if (client.session.isOpen()) {
      client.session.sendMessage(
          new TextMessage(
              json.writeValueAsString(
                  Map.of(
                      "protocolVersion",
                      1,
                      "type",
                      type,
                      "documentId",
                      client.connection.documentId(),
                      "connectionId",
                      client.id,
                      "data",
                      data))));
      metrics.counter("collab.websocket.messages", "direction", "out").increment();
    }
  }

  private void error(Client client, String code) {
    metrics.counter("collab.websocket.errors", "code", code).increment();
    try {
      send(client, "ERROR", Map.of("code", code));
    } catch (Exception ignored) {
    }
  }

  private void close(Client client, CloseStatus status) {
    try {
      client.session.close(status);
    } catch (IOException ignored) {
    } finally {
      clients.remove(client.session.getId());
      presence.remove(client.connection, client.id);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Client client = clients.remove(session.getId());
    if (client != null) presence.remove(client.connection, client.id);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    Client client = clients.get(session.getId());
    if (client != null) close(client, CloseStatus.SERVER_ERROR);
  }
}
