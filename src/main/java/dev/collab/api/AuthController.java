package dev.collab.api;

import dev.collab.security.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  public record Credentials(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @Size(min = 12, max = 72) String password) {}

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthService.Token register(@Valid @RequestBody Credentials body) {
    return auth.register(body.email(), body.password());
  }

  @PostMapping("/login")
  public AuthService.Token login(@Valid @RequestBody Credentials body) {
    return auth.login(body.email(), body.password());
  }
}
