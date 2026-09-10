package dev.collab.config;

import dev.collab.realtime.*;
import org.springframework.context.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
  private final CollaborationGateway gateway;
  private final TicketHandshake handshake;
  private final EditorProperties properties;

  public WebSocketConfiguration(
      CollaborationGateway gateway, TicketHandshake handshake, EditorProperties properties) {
    this.gateway = gateway;
    this.handshake = handshake;
    this.properties = properties;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(gateway, "/ws/documents/{id}")
        .addInterceptors(handshake)
        .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
  }

  @Bean
  ServletServerContainerFactoryBean webSocketContainer() {
    var container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(32768);
    container.setMaxBinaryMessageBufferSize(32768);
    container.setMaxSessionIdleTimeout(45000L);
    return container;
  }
}
