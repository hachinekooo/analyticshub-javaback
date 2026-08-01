package com.github.analyticshub.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 为了能够多次读取请求体（一次用于签名验证，一次用于 Spring MVC 反序列化）
 * 对 HttpServletRequest 进行包装，将请求体缓存到内存中。
 */
public class CachingHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachingHttpServletRequestWrapper(HttpServletRequest request, int maxBodyBytes) throws IOException {
        super(request);
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.cachedBody = readBody(request, maxBodyBytes);
    }

    private static byte[] readBody(HttpServletRequest request, int maxBodyBytes) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            throw new RequestBodyTooLargeException(maxBodyBytes);
        }

        int initialCapacity = declaredLength > 0
                ? (int) Math.min(declaredLength, maxBodyBytes)
                : Math.min(8 * 1024, maxBodyBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[Math.min(8 * 1024, maxBodyBytes)];
        ServletInputStream input = request.getInputStream();
        long total = 0;
        int read;
        while ((read = input.read(
                buffer,
                0,
                (int) Math.min(buffer.length, (long) maxBodyBytes - total + 1L)
        )) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new RequestBodyTooLargeException(maxBodyBytes);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(this.cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(this.cachedBody)));
    }

    public String getBody() {
        return new String(this.cachedBody, java.nio.charset.StandardCharsets.UTF_8);
    }

    static final class RequestBodyTooLargeException extends IOException {
        RequestBodyTooLargeException(int maxBodyBytes) {
            super("Request body exceeds configured limit of " + maxBodyBytes + " bytes");
        }
    }

    private static class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream inputStream;

        public CachedServletInputStream(byte[] cachedBody) {
            this.inputStream = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
            return inputStream.read();
        }
    }
}
