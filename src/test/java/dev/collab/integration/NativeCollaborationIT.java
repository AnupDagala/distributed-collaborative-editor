package dev.collab.integration;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in contract against dedicated real local services when Docker is unavailable. */
@EnabledIfEnvironmentVariable(named = "EDITOR_NATIVE_TESTS", matches = "true")
class NativeCollaborationIT extends CollaborationContract {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("EDITOR_TEST_DATABASE_URL"));
    registry.add("spring.datasource.username", () -> System.getenv("EDITOR_TEST_DATABASE_USER"));
    registry.add(
        "spring.datasource.password", () -> System.getenv("EDITOR_TEST_DATABASE_PASSWORD"));
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", () -> System.getenv("EDITOR_TEST_REDIS_PORT"));
    registry.add("spring.data.redis.password", () -> System.getenv("EDITOR_TEST_REDIS_PASSWORD"));
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String secret = Base64.getEncoder().encodeToString(key);
    registry.add("editor.jwt-secret", () -> secret);
  }
}
