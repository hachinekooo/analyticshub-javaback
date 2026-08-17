package com.github.analyticshub.security;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.config.ActorLinkSecurityProperties;
import com.github.analyticshub.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;

/**
 * 只保护 actor-link 内部接口的服务间 HMAC 认证。
 *
 * <p>网络 loopback 限制是纵深防御；真正的调用方和项目授权由专用服务凭据决定。</p>
 */
public class ActorLinkAuthenticationFilter extends OncePerRequestFilter {

    static final String ENDPOINT = "/internal/v1/analytics/actor-links";

    private final ObjectMapper objectMapper;
    private final ActorLinkSecurityProperties properties;

    public ActorLinkAuthenticationFilter(
            ObjectMapper objectMapper,
            ActorLinkSecurityProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPathSecurityPolicy.inspect(request).isPathOrDescendant(ENDPOINT);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RequestPathSecurityPolicy.Inspection path = RequestPathSecurityPolicy.inspect(request);
        if (RequestPathSecurityPolicy.rejectIfUnsafe(path, response, objectMapper)) {
            return;
        }
        if (!properties.isEnabled()) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "ACTOR_LINK_DISABLED", "actor-link 服务未启用");
            return;
        }
        if (properties.isRequireLoopback() && !isLoopback(request.getRemoteAddr())) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "ACTOR_LINK_NETWORK_FORBIDDEN", "actor-link 仅允许受信网络调用");
            return;
        }

        CachingHttpServletRequestWrapper wrapped;
        try {
            wrapped = new CachingHttpServletRequestWrapper(request, properties.getMaxRequestBodyBytes());
        } catch (CachingHttpServletRequestWrapper.RequestBodyTooLargeException exception) {
            sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "REQUEST_BODY_TOO_LARGE", "请求体超过允许大小");
            return;
        }

        String serviceId = normalizedHeader(wrapped, "X-Service-ID");
        String projectId = normalizedHeader(wrapped, "X-Project-ID");
        String timestamp = normalizedHeader(wrapped, "X-Timestamp");
        String idempotencyKey = normalizedHeader(wrapped, "X-Idempotency-Key");
        String signature = normalizedHeader(wrapped, "X-Service-Signature");
        if (serviceId == null || projectId == null || timestamp == null
                || idempotencyKey == null || signature == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "ACTOR_LINK_HEADERS_MISSING", "缺少服务认证请求头");
            return;
        }

        ActorLinkSecurityProperties.Client client = properties.requireClient(serviceId, projectId);
        if (client == null) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "ACTOR_LINK_CLIENT_FORBIDDEN", "调用方无权写入该项目");
            return;
        }
        if (!isFresh(timestamp)) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "ACTOR_LINK_TIMESTAMP_INVALID", "服务请求时间戳无效或已过期");
            return;
        }

        String bodyHash = CryptoUtils.sha256Hex(wrapped.getBodyBytes());
        String signatureData = String.join("|",
                wrapped.getMethod(),
                path.applicationPath(),
                timestamp,
                serviceId,
                projectId,
                idempotencyKey,
                bodyHash
        );
        if (!CryptoUtils.verifySignature(signatureData, signature, client.secret())) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "ACTOR_LINK_SIGNATURE_INVALID", "服务请求签名无效");
            return;
        }

        filterChain.doFilter(wrapped, response);
    }

    private boolean isFresh(String timestamp) {
        try {
            long requestTime = Long.parseLong(timestamp);
            return isFresh(requestTime, System.currentTimeMillis(), properties.getSignatureValidityMs());
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static boolean isFresh(long requestTime, long now, long validityMs) {
        if (validityMs < 0) {
            return false;
        }
        long earliestAccepted = now < Long.MIN_VALUE + validityMs
                ? Long.MIN_VALUE
                : now - validityMs;
        long latestAccepted = now > Long.MAX_VALUE - validityMs
                ? Long.MAX_VALUE
                : now + validityMs;
        return requestTime >= earliestAccepted && requestTime <= latestAccepted;
    }

    private static boolean isLoopback(String rawAddress) {
        try {
            return rawAddress != null && InetAddress.getByName(rawAddress).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private static String normalizedHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            return null;
        }
        return value;
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
    }
}
