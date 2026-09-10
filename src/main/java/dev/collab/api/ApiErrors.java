package dev.collab.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> known(ApiException ex, HttpServletRequest request) {
    return response(ex.status(), ex.code(), request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception ex, HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request);
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception ex, HttpServletRequest request) {
    return response(HttpStatus.CONFLICT, "CONFLICT", request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> internal(Exception ex, HttpServletRequest request) {
    if (ex instanceof org.springframework.web.ErrorResponse error
        && error.getStatusCode().is4xxClientError())
      return response(
          HttpStatus.valueOf(error.getStatusCode().value()), "INVALID_REQUEST", request);
    log.error(
        "Request failed requestId={} exceptionType={}",
        request.getAttribute("requestId"),
        ex.getClass().getSimpleName());
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request);
  }

  private ResponseEntity<?> response(HttpStatus status, String code, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "requestId", String.valueOf(request.getAttribute("requestId"))));
  }
}
