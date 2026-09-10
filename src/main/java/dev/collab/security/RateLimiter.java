package dev.collab.security;

import dev.collab.api.ApiException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          "local n=redis.call('INCRBY',KEYS[1],ARGV[1]); if n==tonumber(ARGV[1]) then"
              + " redis.call('PEXPIRE',KEYS[1],ARGV[2]) end; return n",
          Long.class);

  public RateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void require(String category, String identity, int cost, int limit, int windowMs) {
    try {
      Long count =
          redis.execute(
              SCRIPT,
              List.of("collab:rate:" + category + ":" + Digests.sha256(identity)),
              String.valueOf(cost),
              String.valueOf(windowMs));
      if (count == null) throw new IllegalStateException("Missing rate result");
      if (count > limit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
    } catch (ApiException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE");
    }
  }
}
