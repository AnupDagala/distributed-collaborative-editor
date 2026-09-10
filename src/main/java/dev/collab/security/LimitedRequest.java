package dev.collab.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Enforces the byte cap even when Content-Length is absent (chunked requests). */
final class LimitedRequest extends HttpServletRequestWrapper {
  private final ServletInputStream input;

  LimitedRequest(HttpServletRequest request, int limit) throws IOException {
    super(request);
    ServletInputStream delegate = request.getInputStream();
    input =
        new ServletInputStream() {
          private int count;

          private void consumed(int size) throws IOException {
            if (size > 0 && (count += size) > limit)
              throw new IOException("Request body limit exceeded");
          }

          @Override
          public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) consumed(1);
            return value;
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            int size = delegate.read(bytes, offset, Math.min(length, limit - count + 1));
            consumed(size);
            return size;
          }

          @Override
          public boolean isFinished() {
            return delegate.isFinished();
          }

          @Override
          public boolean isReady() {
            return delegate.isReady();
          }

          @Override
          public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
          }
        };
  }

  @Override
  public ServletInputStream getInputStream() {
    return input;
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
  }
}
