package com.github.analyticshub.security;

import com.github.analyticshub.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.PathContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 为各认证过滤器统一判定请求路径；拒绝可能被 Spring 路由与 Servlet 过滤器解释成不同接口的路径写法。
 */
public final class RequestPathSecurityPolicy {

    private static final String INSPECTION_ATTRIBUTE =
            RequestPathSecurityPolicy.class.getName() + ".inspection";

    private RequestPathSecurityPolicy() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Inspection inspect(HttpServletRequest request) {
        Object cached = request.getAttribute(INSPECTION_ATTRIBUTE);
        if (cached instanceof Inspection inspection) {
            return inspection;
        }

        Inspection inspection = inspect(
                request.getRequestURI(),
                request.getContextPath()
        );
        request.setAttribute(INSPECTION_ATTRIBUTE, inspection);
        return inspection;
    }

    static Inspection inspect(String requestUri, String contextPath) {
        if (requestUri == null || requestUri.isEmpty() || requestUri.charAt(0) != '/') {
            return new Inspection("", Namespace.OTHER, true);
        }

        String normalizedContextPath = contextPath == null ? "" : contextPath;
        String applicationPath;
        if (normalizedContextPath.isEmpty()) {
            applicationPath = requestUri;
        } else if (requestUri.equals(normalizedContextPath)) {
            applicationPath = "/";
        } else if (requestUri.startsWith(normalizedContextPath + "/")) {
            applicationPath = requestUri.substring(normalizedContextPath.length());
        } else {
            // 路径不属于已声明的应用上下文时，不再按正常路由识别。
            return new Inspection("", Namespace.OTHER, true);
        }

        Namespace namespace = resolveNamespace(applicationPath);
        boolean ambiguous = namespace != Namespace.OTHER
                && isAmbiguousRequestTarget(requestUri, applicationPath);
        return new Inspection(applicationPath, namespace, ambiguous);
    }

    public static boolean rejectIfUnsafe(
            Inspection inspection,
            HttpServletResponse response,
            ObjectMapper objectMapper
    ) throws IOException {
        if (!inspection.unsafe()) {
            return false;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(
                        "INVALID_REQUEST_PATH",
                        "请求路径包含不支持或存在歧义的编码"
                )
        ));
        return true;
    }

    private static Namespace resolveNamespace(String applicationPath) {
        try {
            PathContainer path = PathContainer.parsePath(applicationPath);
            for (PathContainer.Element element : path.elements()) {
                if (!(element instanceof PathContainer.PathSegment segment)) {
                    continue;
                }
                return switch (namespaceToken(segment.valueToMatch())) {
                    case "api" -> Namespace.API;
                    case "actuator" -> Namespace.ACTUATOR;
                    case "internal" -> Namespace.INTERNAL;
                    default -> Namespace.OTHER;
                };
            }
        } catch (IllegalArgumentException exception) {
            // 保留可识别的原始路径前缀，让后续校验能拒绝有歧义的受保护路径。
            if (applicationPath.equals("/api") || applicationPath.startsWith("/api/")) {
                return Namespace.API;
            }
            if (applicationPath.equals("/actuator") || applicationPath.startsWith("/actuator/")) {
                return Namespace.ACTUATOR;
            }
            if (applicationPath.equals("/internal") || applicationPath.startsWith("/internal/")) {
                return Namespace.INTERNAL;
            }
        }
        return Namespace.OTHER;
    }

    private static String namespaceToken(String firstSegment) {
        int boundary = firstSegment.length();
        for (char delimiter : new char[]{';', '/', '\\'}) {
            int index = firstSegment.indexOf(delimiter);
            if (index >= 0) {
                boundary = Math.min(boundary, index);
            }
        }
        return firstSegment.substring(0, boundary);
    }

    private static boolean isAmbiguousRequestTarget(String requestUri, String applicationPath) {
        if (requestUri.indexOf(';') >= 0
                || requestUri.indexOf('%') >= 0
                || requestUri.indexOf('\\') >= 0
                || applicationPath.contains("//")) {
            return true;
        }

        try {
            PathContainer path = PathContainer.parsePath(applicationPath);
            for (PathContainer.Element element : path.elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    String value = segment.valueToMatch();
                    if (".".equals(value) || "..".equals(value) || containsUnsafeControl(value)) {
                        return true;
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            return true;
        }
        return containsUnsafeControl(applicationPath);
    }

    private static boolean containsUnsafeControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT);
    }

    public enum Namespace {
        API,
        ACTUATOR,
        INTERNAL,
        OTHER
    }

    public record Inspection(String applicationPath, Namespace namespace, boolean unsafe) {

        public boolean isPathOrDescendant(String basePath) {
            return applicationPath.equals(basePath) || applicationPath.startsWith(basePath + "/");
        }
    }
}
