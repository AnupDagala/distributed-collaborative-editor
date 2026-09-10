package dev.collab.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.collab.security.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecretKeySpec signingKey(EditorProperties properties) {
    byte[] key = Base64.getDecoder().decode(properties.jwtSecret());
    if (key.length < 32)
      throw new IllegalArgumentException("JWT_SECRET must encode at least 32 random bytes");
    return new SecretKeySpec(key, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKeySpec key, EditorProperties properties) {
    var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> audience =
        jwt ->
            jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    OAuth2TokenValidator<Jwt> subject =
        jwt -> {
          try {
            UUID.fromString(jwt.getSubject());
            return OAuth2TokenValidatorResult.success();
          } catch (Exception ex) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
          }
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuer()), audience, subject));
    return decoder;
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http, RateLimiter limiter, EditorProperties properties) throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(properties.allowedOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setExposedHeaders(List.of("X-Request-ID"));
    cors.setMaxAge(600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return http.csrf(csrf -> csrf.disable())
        .cors(c -> c.configurationSource(source))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/**",
                        "/ws/**",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/error")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasAuthority("SCOPE_metrics.read")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) -> {
                          response.setStatus(401);
                          response.setContentType("application/json");
                          response.getWriter().write("{\"code\":\"UNAUTHORIZED\"}");
                        })
                    .accessDeniedHandler(
                        (request, response, ex) -> {
                          response.setStatus(403);
                          response.setContentType("application/json");
                          response.getWriter().write("{\"code\":\"ACCESS_DENIED\"}");
                        }))
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (request, response, ex) -> {
                          response.setStatus(401);
                          response.setContentType("application/json");
                          response.getWriter().write("{\"code\":\"INVALID_TOKEN\"}");
                        }))
        .addFilterAfter(new RequestGuard(limiter), BearerTokenAuthenticationFilter.class)
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .build();
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictJson() {
    return builder ->
        builder.postConfigurer(
            mapper -> {
              mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
              mapper
                  .getFactory()
                  .setStreamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(32000)
                          .maxNumberLength(18)
                          .build());
            });
  }
}
