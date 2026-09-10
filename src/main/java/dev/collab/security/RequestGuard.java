package dev.collab.security;

import dev.collab.api.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestGuard extends OncePerRequestFilter {
  private final RateLimiter limiter;

  public RequestGuard(RateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute("requestId", id);
    response.setHeader("X-Request-ID", id);
    response.setHeader("Cache-Control", "no-store");
    try {
      if (request.getRequestURI().startsWith("/api/")) {
        if (request.getContentLengthLong() > 32768) {
          response.sendError(413);
          return;
        }
        if (request.getRequestURI().startsWith("/api/v1/auth/"))
          limiter.require("auth", request.getRemoteAddr(), 1, 20, 60000);
        else {
          var authentication = SecurityContextHolder.getContext().getAuthentication();
          String identity =
              authentication != null && authentication.isAuthenticated()
                  ? authentication.getName()
                  : request.getRemoteAddr();
          limiter.require("http", identity, 1, 300, 60000);
        }
      }
      chain.doFilter(
          request.getRequestURI().startsWith("/api/")
              ? new LimitedRequest(request, 32768)
              : request,
          response);
    } catch (ApiException ex) {
      response.setStatus(ex.status().value());
      response.setContentType("application/json");
      if (ex.status().value() == 429) response.setHeader("Retry-After", "60");
      response.getWriter().write("{\"code\":\"" + ex.code() + "\",\"requestId\":\"" + id + "\"}");
    }
  }
}
