package dev.collab.security;

import dev.collab.api.ApiException;
import dev.collab.config.EditorProperties;
import dev.collab.store.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  public record Token(String accessToken, Instant expiresAt, UUID userId, String email) {}

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JwtEncoder encoder;
  private final EditorProperties properties;
  private final String dummy;

  public AuthService(
      UserRepository users,
      PasswordEncoder passwords,
      JwtEncoder encoder,
      EditorProperties properties) {
    this.users = users;
    this.passwords = passwords;
    this.encoder = encoder;
    this.properties = properties;
    dummy = passwords.encode(Digests.token());
  }

  @Transactional
  public Token register(String email, String password) {
    validatePassword(password);
    String canonical = email.trim().toLowerCase(Locale.ROOT);
    if (users.findByEmail(canonical).isPresent())
      throw ApiException.conflict("ACCOUNT_UNAVAILABLE");
    return token(
        users.saveAndFlush(
            new UserEntity(UUID.randomUUID(), canonical, passwords.encode(password))));
  }

  @Transactional(readOnly = true)
  public Token login(String email, String password) {
    validatePassword(password);
    var user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
    boolean valid = passwords.matches(password, user.map(UserEntity::passwordHash).orElse(dummy));
    if (!valid || user.isEmpty())
      throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    return token(user.get());
  }

  private void validatePassword(String password) {
    if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Password must be 12 characters and at most 72 UTF-8 bytes");
  }

  private Token token(UserEntity user) {
    Instant now = Instant.now(), expires = now.plusSeconds(900);
    var claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(expires)
            .id(UUID.randomUUID().toString())
            .build();
    String jwt =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new Token(jwt, expires, user.id(), user.email());
  }
}
