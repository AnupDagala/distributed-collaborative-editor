package dev.collab.api;

import dev.collab.security.RateLimiter;
import dev.collab.service.CollaborationModels.*;
import dev.collab.service.CollaborationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
  public record Create(@NotBlank @Size(max = 160) String title) {}

  public record Share(@NotBlank @Email @Size(max = 254) String email, @NotNull Role role) {}

  public record Connect(UUID actorId) {}

  private final CollaborationService service;
  private final RateLimiter limiter;

  public DocumentController(CollaborationService service, RateLimiter limiter) {
    this.service = service;
    this.limiter = limiter;
  }

  private UUID user(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create body) {
    return service.create(user(jwt), body.title());
  }

  @GetMapping
  public List<DocumentView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    return service.list(user(jwt), offset, limit);
  }

  @GetMapping("/{id}")
  public DocumentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(id, user(jwt));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.delete(id, user(jwt));
  }

  @GetMapping("/{id}/members")
  public List<Member> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.members(id, user(jwt));
  }

  @PostMapping("/{id}/members")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void share(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Share body) {
    service.share(id, user(jwt), body.email(), body.role());
  }

  @DeleteMapping("/{id}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId) {
    service.removeMember(id, user(jwt), userId);
  }

  @GetMapping("/{id}/history")
  public Page history(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "100") int limit) {
    return service.history(id, user(jwt), after, limit);
  }

  @GetMapping("/{id}/snapshot")
  public Snapshot snapshot(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.snapshot(id, user(jwt));
  }

  @PostMapping("/{id}/connections")
  public Ticket ticket(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody Connect body) {
    limiter.require("ticket", jwt.getSubject(), 1, 30, 60000);
    return service.ticket(id, user(jwt), body.actorId(), jwt.getExpiresAt());
  }
}
