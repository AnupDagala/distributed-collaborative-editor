package dev.collab.realtime;

import dev.collab.api.ApiException;
import dev.collab.config.EditorProperties;
import dev.collab.security.RateLimiter;
import dev.collab.service.CollaborationService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class TicketHandshake implements HandshakeInterceptor {
  private final CollaborationService service;
  private final EditorProperties properties;
  private final RateLimiter limiter;

  public TicketHandshake(
      CollaborationService service, EditorProperties properties, RateLimiter limiter) {
    this.service = service;
    this.properties = properties;
    this.limiter = limiter;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    try {
      if (!properties.allowedOrigins().contains(request.getHeaders().getOrigin()))
        throw ApiException.forbidden();
      String address =
          request.getRemoteAddress() == null
              ? "unknown"
              : request.getRemoteAddress().getAddress().getHostAddress();
      limiter.require("handshake", address, 1, 60, 60000);
      List<String> protocols =
          request.getHeaders().getOrEmpty("Sec-WebSocket-Protocol").stream()
              .flatMap(v -> Arrays.stream(v.split(",")))
              .map(String::trim)
              .toList();
      if (!protocols.contains("collab.v1")) throw new IllegalArgumentException("Protocol missing");
      var credentials =
          protocols.stream().filter(s -> s.matches("ticket\\.[A-Za-z0-9_-]{43}")).toList();
      if (credentials.size() != 1 || protocols.size() != 2)
        throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TICKET");
      String path = request.getURI().getPath();
      UUID doc = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
      attributes.put("connection", service.consumeTicket(doc, credentials.getFirst().substring(7)));
      return true;
    } catch (ApiException ex) {
      response.setStatusCode(ex.status());
      return false;
    } catch (RuntimeException ex) {
      response.setStatusCode(HttpStatus.BAD_REQUEST);
      return false;
    }
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception ex) {}
}
