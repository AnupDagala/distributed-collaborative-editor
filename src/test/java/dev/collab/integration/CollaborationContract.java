package dev.collab.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.collab.api.ApiException;
import dev.collab.crdt.*;
import dev.collab.security.AuthService;
import dev.collab.service.CollaborationModels.*;
import dev.collab.service.CollaborationService;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"editor.snapshot-interval=5", "logging.level.root=WARN"})
@AutoConfigureMockMvc
abstract class CollaborationContract {
  @Autowired CollaborationService service;
  @Autowired AuthService auth;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
  @Autowired dev.collab.config.EditorProperties properties;
  @LocalServerPort int port;

  record Fixture(AuthService.Token user, UUID doc, Connection connection) {}

  AuthService.Token user() {
    return auth.register(UUID.randomUUID() + "@example.test", "Test-only-" + UUID.randomUUID());
  }

  Fixture fixture() {
    var user = user();
    UUID doc = service.create(user.userId(), "Test document").id();
    var ticket = service.ticket(doc, user.userId(), null, user.expiresAt());
    return new Fixture(user, doc, service.consumeTicket(doc, ticket.ticket()));
  }

  Operation insert(Connection c, long seq, long clock, Id pred, String value) {
    return Operation.insert(new Id(c.actorId(), seq), clock, pred, value);
  }

  SequenceCrdt replica(UUID doc, UUID user) {
    return SequenceCrdt.restore(
        service.snapshot(doc, user).state(), SequenceCrdt.Limits.document());
  }

  @Test
  void authenticationHashesPasswordsAndProtectsRest() throws Exception {
    String email = UUID.randomUUID() + "@example.test", password = "Test-only-" + UUID.randomUUID();
    String response =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var token = json.readTree(response);
    String stored =
        db.queryForObject("select password_hash from app_users where email=?", String.class, email);
    assertNotEquals(password, stored);
    assertTrue(stored.startsWith("$2a$12$"));
    mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
    var expiredClaims =
        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .subject(token.get("userId").asText())
            .issuedAt(Instant.now().minusSeconds(300))
            .expiresAt(Instant.now().minusSeconds(120))
            .build();
    var expiredHeader =
        org.springframework.security.oauth2.jwt.JwsHeader.with(
                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
            .build();
    String expired =
        jwtEncoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    expiredHeader, expiredClaims))
            .getTokenValue();
    mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/documents")
                .header("Authorization", "Bearer " + token.get("accessToken").asText()))
        .andExpect(status().isOk());
    mvc.perform(
            get("/actuator/prometheus")
                .header("Authorization", "Bearer " + token.get("accessToken").asText()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("email", email, "password", "incorrect-password"))))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", password))))
        .andExpect(status().isOk());
  }

  @Test
  void passwordUnicodeCannotExploitBcryptTruncation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> auth.register(UUID.randomUUID() + "@example.test", "🌍".repeat(30)));
  }

  @Test
  void strictJsonAndCorsRejectUnexpectedInput() throws Exception {
    var f = fixture();
    String jwt = f.user().accessToken();
    mvc.perform(
            post("/api/v1/documents")
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .content("{\"title\":\"x\",\"title\":\"y\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/documents")
                .header("Authorization", "Bearer " + jwt)
                .contentType("application/json")
                .content("{\"title\":\"x\",\"ownerId\":\"spoof\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            options("/api/v1/documents")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void duplicateAckIsDurableAndConflictingReuseRollsBack() {
    var f = fixture();
    var op = insert(f.connection(), 1, 1, null, "A");
    var first = service.append(f.connection(), List.of(op));
    var duplicate = service.append(f.connection(), List.of(op));
    assertEquals(first.getFirst().serverSequence(), duplicate.getFirst().serverSequence());
    assertTrue(duplicate.getFirst().duplicate());
    assertThrows(
        ApiException.class,
        () -> service.append(f.connection(), List.of(insert(f.connection(), 1, 1, null, "B"))));
    assertEquals(1, service.get(f.doc(), f.user().userId()).sequence());
    assertEquals("A", replica(f.doc(), f.user().userId()).text());
  }

  @Test
  void mixedInvalidBatchLeavesNoPartialCommit() {
    var f = fixture();
    var first = insert(f.connection(), 1, 1, null, "A");
    var gap = insert(f.connection(), 3, 2, first.elementId(), "B");
    assertThrows(ApiException.class, () -> service.append(f.connection(), List.of(first, gap)));
    assertEquals(0, service.get(f.doc(), f.user().userId()).sequence());
    assertEquals("", replica(f.doc(), f.user().userId()).text());
    service.append(f.connection(), List.of(first));
    assertEquals(1, service.get(f.doc(), f.user().userId()).sequence());
  }

  @Test
  void membershipAuthorizationAndRevocationApplyToOpenActors() {
    var f = fixture();
    var other = user();
    assertThrows(ApiException.class, () -> service.snapshot(f.doc(), other.userId()));
    service.share(f.doc(), f.user().userId(), other.email(), Role.VIEWER);
    var ticket = service.ticket(f.doc(), other.userId(), null, other.expiresAt());
    var viewer = service.consumeTicket(f.doc(), ticket.ticket());
    var op = insert(viewer, 1, 1, null, "V");
    assertThrows(ApiException.class, () -> service.append(viewer, List.of(op)));
    assertThrows(
        ApiException.class,
        () -> service.share(f.doc(), other.userId(), f.user().email(), Role.EDITOR));
    service.share(f.doc(), f.user().userId(), other.email(), Role.EDITOR);
    service.append(viewer, List.of(op));
    service.removeMember(f.doc(), f.user().userId(), other.userId());
    assertThrows(ApiException.class, () -> service.catchUp(viewer, 0));
    assertThrows(ApiException.class, () -> service.append(viewer, List.of(op)));
  }

  @Test
  void ticketsAreSingleUseAndReconnectFencesOldGeneration() {
    var f = fixture();
    var ticket =
        service.ticket(f.doc(), f.user().userId(), f.connection().actorId(), f.user().expiresAt());
    assertThrows(ApiException.class, () -> service.validate(f.connection()));
    var next = service.consumeTicket(f.doc(), ticket.ticket());
    assertThrows(ApiException.class, () -> service.consumeTicket(f.doc(), ticket.ticket()));
    assertEquals(Role.OWNER, service.validate(next));
  }

  @Test
  void actorSpoofingSequenceGapsClockJumpsAndExpiredSessionsAreRejected() {
    var f = fixture();
    var alien = fixture();
    assertThrows(
        ApiException.class,
        () -> service.append(f.connection(), List.of(insert(alien.connection(), 1, 1, null, "x"))));
    assertThrows(
        ApiException.class,
        () ->
            service.ticket(
                f.doc(), f.user().userId(), alien.connection().actorId(), f.user().expiresAt()));
    assertThrows(
        ApiException.class,
        () -> service.append(f.connection(), List.of(insert(f.connection(), 2, 1, null, "x"))));
    assertThrows(
        ApiException.class,
        () -> service.append(f.connection(), List.of(insert(f.connection(), 1, 5000, null, "x"))));
    var expired =
        new Connection(
            f.user().userId(),
            f.doc(),
            f.connection().actorId(),
            f.connection().generation(),
            Instant.now().minusSeconds(1));
    assertThrows(ApiException.class, () -> service.validate(expired));
  }

  @Test
  void snapshotsRestorePendingDependenciesAndReplaySuffix() {
    var f = fixture();
    var peerTicket = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
    var peer = service.consumeTicket(f.doc(), peerTicket.ticket());
    var parent = insert(peer, 1, 1, null, "P");
    List<Operation> pending = new ArrayList<>();
    for (int i = 1; i <= 6; i++)
      pending.add(insert(f.connection(), i, i + 1, parent.elementId(), "x"));
    service.append(f.connection(), pending);
    assertEquals(5, service.snapshotWatermark(f.doc()));
    assertEquals(6, replica(f.doc(), f.user().userId()).pendingCount());
    service.append(peer, List.of(parent));
    assertEquals("Pxxxxxx", replica(f.doc(), f.user().userId()).text());
    assertEquals(0, replica(f.doc(), f.user().userId()).pendingCount());
    var page = service.history(f.doc(), f.user().userId(), 5, 100);
    assertEquals(
        List.of(6L, 7L), page.operations().stream().map(LogEntry::serverSequence).toList());
    var restored = replica(f.doc(), f.user().userId());
    page.operations().forEach(e -> restored.apply(e.operation()));
    assertEquals("Pxxxxxx", restored.text());
  }

  @Test
  void snapshotCorruptionIsDetected() {
    var f = fixture();
    List<Operation> ops = new ArrayList<>();
    for (int i = 1; i <= 5; i++) ops.add(insert(f.connection(), i, i, null, "x"));
    service.append(f.connection(), ops);
    db.update("update snapshots set checksum=repeat('0',64) where document_id=?", f.doc());
    assertThrows(IllegalStateException.class, () -> service.snapshot(f.doc(), f.user().userId()));
  }

  @Test
  void concurrentTransactionsAssignContiguousWatermarksAndConverge() throws Exception {
    var f = fixture();
    var ticket = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
    var peer = service.consumeTicket(f.doc(), ticket.ticket());
    List<Operation> left = List.of(insert(f.connection(), 1, 1, null, "A")),
        right = List.of(insert(peer, 1, 1, null, "B"));
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = pool.submit(() -> service.append(f.connection(), left));
      var b = pool.submit(() -> service.append(peer, right));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    var log = service.history(f.doc(), f.user().userId(), 0, 100);
    assertEquals(List.of(1L, 2L), log.operations().stream().map(LogEntry::serverSequence).toList());
    var one = new SequenceCrdt();
    left.forEach(one::apply);
    right.forEach(one::apply);
    var two = new SequenceCrdt();
    right.forEach(two::apply);
    left.forEach(two::apply);
    assertEquals(one.text(), two.text());
    assertEquals(one.text(), replica(f.doc(), f.user().userId()).text());
  }

  @Test
  void deletedDocumentsCloseAccessAndCannotAcceptEdits() {
    var f = fixture();
    service.delete(f.doc(), f.user().userId());
    assertThrows(ApiException.class, () -> service.validate(f.connection()));
    assertThrows(
        ApiException.class,
        () -> service.append(f.connection(), List.of(insert(f.connection(), 1, 1, null, "x"))));
    assertTrue(service.list(f.user().userId(), 0, 100).isEmpty());
  }

  @Test
  void websocketHandshakeRequiresTicketAndOrigin() throws Exception {
    var f = fixture();
    try (var client = HttpClient.newHttpClient()) {
      assertThrows(
          CompletionException.class,
          () ->
              client
                  .newWebSocketBuilder()
                  .header("Origin", "http://localhost:5173")
                  .subprotocols("collab.v1")
                  .buildAsync(
                      URI.create("ws://localhost:" + port + "/ws/documents/" + f.doc()),
                      new Inbox())
                  .join());
      var ticket = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
      assertThrows(
          CompletionException.class,
          () ->
              client
                  .newWebSocketBuilder()
                  .header("Origin", "https://evil.example")
                  .subprotocols("collab.v1", "ticket." + ticket.ticket())
                  .buildAsync(
                      URI.create("ws://localhost:" + port + ticket.socketPath()), new Inbox())
                  .join());
    }
  }

  @Test
  void websocketCommitsBroadcastsAndRecoversFromAcknowledgmentLoss() throws Exception {
    var f = fixture();
    var t1 = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
    var t2 = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
    var http = HttpClient.newHttpClient();
    try {
      var a = new Inbox();
      var b = new Inbox();
      WebSocket wa = connect(http, t1, a), wb = connect(http, t2, b);
      send(wa, f.doc(), t1.actorId(), "JOIN_DOCUMENT", Map.of("afterSequence", 0));
      send(wb, f.doc(), t2.actorId(), "JOIN_DOCUMENT", Map.of("afterSequence", 0));
      a.await("SYNC_RESPONSE", json);
      b.await("SYNC_RESPONSE", json);
      var op = Operation.insert(new Id(t1.actorId(), 1), 1, null, "🌍");
      send(wa, f.doc(), t1.actorId(), "EDIT", Map.of("operations", List.of(op)));
      a.await("ACK", json);
      JsonNode broadcast = b.untilSequence(1, json);
      assertEquals(1, broadcast.path("data").path("throughSequence").asLong());
      wa.abort();
      var again = service.ticket(f.doc(), f.user().userId(), t1.actorId(), f.user().expiresAt());
      var c = new Inbox();
      WebSocket wc = connect(http, again, c);
      send(wc, f.doc(), t1.actorId(), "JOIN_DOCUMENT", Map.of("afterSequence", 0));
      c.untilSequence(1, json);
      send(wc, f.doc(), t1.actorId(), "EDIT", Map.of("operations", List.of(op)));
      assertTrue(
          c.await("ACK", json)
              .path("data")
              .path("acknowledgments")
              .get(0)
              .path("duplicate")
              .asBoolean());
      assertEquals(1, service.get(f.doc(), f.user().userId()).sequence());
      wc.sendClose(1000, "done").join();
      wb.sendClose(1000, "done").join();
    } finally {
      http.shutdownNow();
    }
  }

  @Test
  void binaryOversizedAndMalformedWebSocketMessagesAreRejected() throws Exception {
    var f = fixture();
    var http = HttpClient.newHttpClient();
    try {
      for (int kind = 0; kind < 4; kind++) {
        var ticket = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
        var inbox = new Inbox();
        WebSocket ws = connect(http, ticket, inbox);
        if (kind == 0) ws.sendBinary(java.nio.ByteBuffer.wrap(new byte[] {1}), true).join();
        else if (kind == 1) ws.sendText("x".repeat(32769), true).join();
        else if (kind == 2) ws.sendText("{\"protocolVersion\":1,\"unexpected\":true}", true).join();
        else ws.sendText("€".repeat(12000), true).join();
        assertTrue(inbox.closed.await(5, TimeUnit.SECONDS), "Expected transport close");
        assertEquals(kind == 0 ? 1003 : kind == 2 ? 1007 : 1009, inbox.closeCode);
      }
      assertEquals(0, service.get(f.doc(), f.user().userId()).sequence());
    } finally {
      http.shutdownNow();
    }
  }

  @Test
  void snapshotNegotiationDoesNotDiscardRacingUnacknowledgedEdit() throws Exception {
    var f = fixture();
    for (int start = 1; start <= 129; start += 32) {
      List<Operation> ops = new ArrayList<>();
      for (int i = start; i < Math.min(start + 32, 131); i++)
        ops.add(insert(f.connection(), i, i, null, "x"));
      service.append(f.connection(), ops);
    }
    var ticket = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
    var inbox = new Inbox();
    var http = HttpClient.newHttpClient();
    try {
      var ws = connect(http, ticket, inbox);
      send(ws, f.doc(), ticket.actorId(), "JOIN_DOCUMENT", Map.of("afterSequence", 0));
      inbox.await("SYNC_REQUIRED", json);
      var op = Operation.insert(new Id(ticket.actorId(), 1), 131, null, "🌍");
      send(ws, f.doc(), ticket.actorId(), "EDIT", Map.of("operations", List.of(op)));
      inbox.await("SYNC_REQUIRED", json);
      assertEquals(130, service.get(f.doc(), f.user().userId()).sequence());
      send(ws, f.doc(), ticket.actorId(), "SYNC_REQUEST", Map.of("afterSequence", 130));
      inbox.await("SYNC_RESPONSE", json);
      send(ws, f.doc(), ticket.actorId(), "EDIT", Map.of("operations", List.of(op)));
      inbox.await("ACK", json);
      assertEquals(131, service.get(f.doc(), f.user().userId()).sequence());
      ws.sendClose(1000, "done").join();
    } finally {
      http.shutdownNow();
    }
  }

  @Test
  void databaseRepairDeliversCommitWithNoNotification() throws Exception {
    var f = fixture();
    var ticket = service.ticket(f.doc(), f.user().userId(), null, f.user().expiresAt());
    var inbox = new Inbox();
    var http = HttpClient.newHttpClient();
    try {
      var ws = connect(http, ticket, inbox);
      send(ws, f.doc(), ticket.actorId(), "JOIN_DOCUMENT", Map.of("afterSequence", 0));
      inbox.await("SYNC_RESPONSE", json);
      var op = insert(f.connection(), 1, 1, null, "R");
      String payload = json.writeValueAsString(op);
      // Simulate another instance's committed transaction with its after-commit hint lost.
      new org.springframework.transaction.support.TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                db.queryForObject(
                    "select id from documents where id=? for update", UUID.class, f.doc());
                db.update(
                    "insert into"
                        + " operations(document_id,actor_id,operation_sequence,server_sequence,payload,checksum)"
                        + " values(?,?,1,1,?,?)",
                    f.doc(),
                    f.connection().actorId(),
                    payload,
                    dev.collab.security.Digests.sha256(op.signature()));
                db.update(
                    "update documents set last_sequence=1,max_lamport=1,version=version+1 where"
                        + " id=?",
                    f.doc());
                db.update(
                    "update editing_actors set last_sequence=1,last_lamport=1 where id=?",
                    f.connection().actorId());
              });
      assertEquals(1, inbox.untilSequence(1, json).path("data").path("throughSequence").asLong());
      ws.sendClose(1000, "done").join();
    } finally {
      http.shutdownNow();
    }
  }

  WebSocket connect(HttpClient http, Ticket ticket, Inbox inbox) {
    return http.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .header("Origin", "http://localhost:5173")
        .subprotocols("collab.v1", "ticket." + ticket.ticket())
        .buildAsync(URI.create("ws://localhost:" + port + ticket.socketPath()), inbox)
        .join();
  }

  void send(WebSocket socket, UUID doc, UUID actor, String type, Map<String, Object> fields)
      throws Exception {
    var message = new HashMap<String, Object>(fields);
    message.put("protocolVersion", 1);
    message.put("documentId", doc);
    message.put("actorId", actor);
    message.put("type", type);
    socket.sendText(json.writeValueAsString(message), true).join();
  }

  static final class Inbox implements WebSocket.Listener {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final StringBuilder partial = new StringBuilder();
    volatile String terminal = "open";
    final CountDownLatch closed = new CountDownLatch(1);
    volatile int closeCode;

    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
      terminal = "closed " + status;
      closeCode = status;
      closed.countDown();
      return null;
    }

    public void onError(WebSocket ws, Throwable error) {
      terminal = "failed " + error.getClass().getSimpleName();
    }

    public void onOpen(WebSocket ws) {
      ws.request(1);
    }

    public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
      partial.append(text);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      ws.request(1);
      return null;
    }

    JsonNode await(String type, ObjectMapper json) throws Exception {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      List<String> received = new ArrayList<>();
      while (System.nanoTime() < end) {
        String raw = messages.poll(200, TimeUnit.MILLISECONDS);
        if (raw != null) {
          JsonNode msg = json.readTree(raw);
          received.add(msg.path("type").asText());
          if (msg.path("type").asText().equals("ERROR"))
            fail("WebSocket error: " + msg.path("data"));
          if (msg.path("type").asText().equals(type)) return msg;
        }
      }
      throw new AssertionError(
          "Timed out waiting for " + type + "; terminal=" + terminal + "; received=" + received);
    }

    JsonNode untilSequence(long sequence, ObjectMapper json) throws Exception {
      for (int i = 0; i < 15; i++) {
        JsonNode msg = await("SYNC_RESPONSE", json);
        if (msg.path("data").path("throughSequence").asLong() >= sequence) return msg;
      }
      throw new AssertionError("Replay cursor did not advance");
    }
  }
}
