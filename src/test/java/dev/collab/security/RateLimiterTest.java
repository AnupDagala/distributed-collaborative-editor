package dev.collab.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.collab.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RateLimiterTest {
  @Test
  void allowsBudgetAndRejectsExcess() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(10L, 11L);
    var limiter = new RateLimiter(redis);
    limiter.require("edit", "identity", 1, 10, 1000);
    assertEquals(
        429,
        assertThrows(ApiException.class, () -> limiter.require("edit", "identity", 1, 10, 1000))
            .status()
            .value());
  }

  @Test
  void redisFailureCannotDisableAbuseProtection() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(new IllegalStateException("redis offline"));
    var ex =
        assertThrows(
            ApiException.class,
            () -> new RateLimiter(redis).require("auth", "identity", 1, 10, 1000));
    assertEquals(503, ex.status().value());
    assertEquals("RATE_LIMIT_UNAVAILABLE", ex.code());
  }
}
