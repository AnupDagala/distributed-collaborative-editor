package dev.collab.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND");
  }

  public static ApiException conflict(String code) {
    return new ApiException(HttpStatus.CONFLICT, code);
  }
}
