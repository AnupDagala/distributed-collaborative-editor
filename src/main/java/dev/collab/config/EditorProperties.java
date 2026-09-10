package dev.collab.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("editor")
public record EditorProperties(
    String jwtSecret,
    String issuer,
    String audience,
    List<String> allowedOrigins,
    int snapshotInterval,
    String instanceId) {
  public EditorProperties {
    if (allowedOrigins == null
        || allowedOrigins.isEmpty()
        || allowedOrigins.stream()
            .anyMatch(
                o ->
                    o.contains("*")
                        || !(o.startsWith("https://")
                            || o.startsWith("http://localhost:")
                            || o.startsWith("http://127.0.0.1:"))))
      throw new IllegalArgumentException(
          "Explicit HTTPS origins (or loopback development origins) required");
    if (snapshotInterval < 1 || snapshotInterval > 1000)
      throw new IllegalArgumentException("Invalid snapshot interval");
  }
}
