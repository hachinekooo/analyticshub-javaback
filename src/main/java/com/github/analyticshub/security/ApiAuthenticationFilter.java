package com.github.analyticshub.security;

import tools.jackson.databind.ObjectMapper;
import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.UUID;

/**
 * API认证过滤器
 * 验证多项目的API Key和HMAC签名
 * 支持项目隔离和动态数据源切换
 */
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(ApiAuthenticationFilter.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;

    private final long signatureValidityMs;
    private final int maxRequestBodyBytes;

    // 不需要 HMAC 认证的路径。管理端与 Actuator 由
    // AdminApiAuthenticationFilter 统一执行 Admin Token 策略。
    private static final String[] PUBLIC_PATHS = {
            "/api/health",
            "/actuator",
            "/api/v1/auth/register",
            "/api/v1/auth/admin-token/verify",
            "/api/public",
            "/api/admin"
    };

    public ApiAuthenticationFilter(MultiDataSourceManager dataSourceManager, 
                                   ObjectMapper objectMapper,
                                   long signatureValidityMs,
                                   int maxRequestBodyBytes) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
        this.signatureValidityMs = signatureValidityMs;
        if (maxRequestBodyBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        }
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        RequestPathSecurityPolicy.Inspection requestPath = RequestPathSecurityPolicy.inspect(request);
        if (RequestPathSecurityPolicy.rejectIfUnsafe(requestPath, response, objectMapper)) {
            return;
        }
        String path = requestPath.applicationPath();
        
        // 跳过公开路径
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 包装请求以支持多次读取 Body（用于签名验证）
        CachingHttpServletRequestWrapper wrappedRequest;
        try {
            wrappedRequest = new CachingHttpServletRequestWrapper(request, maxRequestBodyBytes);
        } catch (CachingHttpServletRequestWrapper.RequestBodyTooLargeException e) {
            sendErrorResponse(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "REQUEST_BODY_TOO_LARGE", "请求体超过允许的大小");
            return;
        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "无法读取请求体", e);
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "READ_ERROR", "无法读取请求数据");
            return;
        }

        try {
            // 1. 提取项目ID（必须）
            String projectId = wrappedRequest.getHeader("X-Project-ID");
            if (projectId == null || projectId.isBlank()) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                        "MISSING_PROJECT_ID", "缺少项目ID，请在请求头 X-Project-ID 传递");
                return;
            }

            // 2. 验证项目是否存在且激活
            MultiDataSourceManager.ProjectConfig projectConfig;
            try {
                projectConfig = dataSourceManager.getProjectConfig(projectId);
            } catch (IllegalArgumentException e) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                        "INVALID_PROJECT", "项目ID格式无效");
                return;
            } catch (Exception e) {
                sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "PROJECT_CONFIG_UNAVAILABLE", "项目配置加载失败");
                return;
            }

            if (projectConfig == null) {
                sendErrorResponse(response, "INVALID_PROJECT", "项目不存在");
                return;
            }
            if (!projectConfig.isActive()) {
                sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
                        "PROJECT_INACTIVE", "项目未激活");
                return;
            }

            // 3. 提取认证请求头
            String apiKey = wrappedRequest.getHeader("X-API-Key");
            String deviceId = wrappedRequest.getHeader("X-Device-ID");
            String userId = wrappedRequest.getHeader("X-User-ID");
            String timestamp = wrappedRequest.getHeader("X-Timestamp");
            String signature = wrappedRequest.getHeader("X-Signature");

            // 4. 验证必需字段
            if (apiKey == null || deviceId == null || userId == null ||
                    timestamp == null || signature == null) {
                sendErrorResponse(response, "MISSING_HEADERS", "缺少必需的请求头");
                return;
            }

            if (!CryptoUtils.isValidUUID(deviceId)) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                        "INVALID_DEVICE_ID", "无效的设备ID格式");
                return;
            }

            if (!isValidUserId(userId)) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                        "INVALID_USER_ID", "无效的用户ID格式");
                return;
            }

            // 5. 验证时间戳（防重放攻击）
            try {
                long requestTime = Long.parseLong(timestamp);
                long currentTime = System.currentTimeMillis();
                long timeDiff = Math.abs(currentTime - requestTime);
                
                // 允许5分钟的时间差
                if (timeDiff > signatureValidityMs) {
                    sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                            "TIMESTAMP_EXPIRED", "请求时间戳已过期");
                    return;
                }
            } catch (NumberFormatException e) {
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                        "INVALID_TIMESTAMP", "无效的时间戳格式");
                return;
            }

            // 6. 从项目数据库查询设备信息
            DataSource dataSource = dataSourceManager.getDataSource(projectId);
            String devicesTable = dataSourceManager.getTableName(projectId, "devices");
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            Device device = queryDevice(jdbcTemplate, devicesTable, apiKey, deviceId, projectId);

            if (device == null) {
                log.log(System.Logger.Level.WARNING, "认证失败: 无效的 API Key 或设备凭据, projectId={0}", projectId);
                sendErrorResponse(response, "INVALID_CREDENTIALS", "无效的API Key或设备ID");
                return;
            }

            // 7. 检查设备是否被封禁
            if (Boolean.TRUE.equals(device.getIsBanned())) {
                log.log(System.Logger.Level.WARNING, "认证失败: 设备已被封禁, projectId={0}", projectId);
                sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
                        "DEVICE_BANNED", "设备已被封禁");
                return;
            }

            // 8. 验证HMAC签名
            String signatureData = CryptoUtils.buildSignatureData(
                    wrappedRequest.getMethod(),
                    wrappedRequest.getRequestURI(),
                    timestamp,
                    deviceId,
                    userId,
                    wrappedRequest.getBody() // 现在包含 Body 进行签名验证
            );

            if (!CryptoUtils.verifySignature(signatureData, signature, device.getSecretKey())) {
                log.log(System.Logger.Level.WARNING, "认证失败: 签名验证失败, projectId={0}", projectId);
                sendErrorResponse(response, "INVALID_SIGNATURE", "签名验证失败");
                return;
            }

            // 9. 设置请求上下文（ThreadLocal），供后续业务链路读取
            RequestContext context = new RequestContext();
            context.setProjectId(projectId);
            context.setDevice(device);
            context.setUserId(userId);
            context.setDataSource(dataSource);
            context.setTablePrefix(projectConfig.tablePrefix());
            RequestContext.set(context);

            // 10. 继续处理请求 (使用包装后的请求)
            filterChain.doFilter(wrappedRequest, response);

        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "认证过滤器异常", e);
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AUTH_SERVICE_UNAVAILABLE", "认证服务暂时不可用");
        } finally {
            // 清理上下文
            RequestContext.clear();
        }
    }

    /**
     * 查询设备信息
     */
    private Device queryDevice(JdbcTemplate jdbcTemplate, String tableName,
                              String apiKey, String deviceId, String projectId) {
        try {
            String sql = String.format(
                    "SELECT * FROM %s WHERE device_id = ?::uuid AND project_id = ? " +
                            "AND (api_key = ? OR (previous_api_key = ? " +
                            "AND previous_credentials_expires_at > NOW()))",
                    tableName
            );

            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Device device = new Device();
                device.setId(rs.getLong("id"));
                device.setDeviceId(UUID.fromString(rs.getString("device_id")));
                String currentApiKey = rs.getString("api_key");
                boolean currentCredential = apiKey.equals(currentApiKey);
                device.setApiKey(currentCredential ? currentApiKey : rs.getString("previous_api_key"));
                device.setSecretKey(currentCredential
                        ? rs.getString("secret_key")
                        : rs.getString("previous_secret_key"));
                device.setDeviceModel(rs.getString("device_model"));
                device.setOsVersion(rs.getString("os_version"));
                device.setAppVersion(rs.getString("app_version"));
                device.setProjectId(rs.getString("project_id"));
                device.setIsBanned(rs.getBoolean("is_banned"));
                device.setBanReason(rs.getString("ban_reason"));
                device.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                device.setLastActiveAt(rs.getTimestamp("last_active_at").toInstant());
                return device;
            }, deviceId, projectId, apiKey, apiKey);
        } catch (EmptyResultDataAccessException exception) {
            log.log(System.Logger.Level.DEBUG, "Device credential lookup returned no match");
            return null;
        }
    }

    /**
     * 判断是否为公开路径
     */
    static boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (isPathOrDescendant(path, publicPath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPathOrDescendant(String path, String basePath) {
        return path != null && (path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    static boolean isValidUserId(String userId) {
        return CryptoUtils.isValidUUID(userId);
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, String code, String message)
            throws IOException {
        sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }

    private void sendErrorResponse(
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        
        ApiResponse<Void> apiResponse = ApiResponse.error(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
