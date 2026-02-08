package com.github.analyticshub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.util.CryptoUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * API认证过滤器
 * 验证多项目的API Key和HMAC签名
 * 支持项目隔离和动态数据源切换
 */
@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(ApiAuthenticationFilter.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    @Value("${app.security.signature-validity-ms:300000}")
    private long signatureValidityMs;

    // 不需要 HMAC 认证的路径
    // 注意：该过滤器是 @Component，可能会被 Servlet 容器全局注册。
    // 因此这里必须显式排除管理端接口（/api/admin/**），避免误拦截仅携带 Admin Token 的请求。
    private static final String[] PUBLIC_PATHS = {
            "/api/health",
            "/actuator",
            "/api/v1/auth/register",
            "/api/v1/auth/admin-token/verify",
            "/api/admin"
    };

    public ApiAuthenticationFilter(MultiDataSourceManager dataSourceManager, ObjectMapper objectMapper) {
        this.dataSourceManager = dataSourceManager;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 跳过公开路径
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 1. 提取项目ID（必须）
            String projectId = request.getHeader("X-Project-ID");
            if (projectId == null || projectId.isBlank()) {
                sendErrorResponse(response, "MISSING_PROJECT_ID", "缺少项目ID，请在请求头 X-Project-ID 传递");
                return;
            }

            // 2. 验证项目是否存在且激活
            MultiDataSourceManager.ProjectConfig projectConfig;
            try {
                projectConfig = dataSourceManager.getProjectConfig(projectId);
            } catch (IllegalArgumentException e) {
                sendErrorResponse(response, "INVALID_PROJECT", "项目ID格式无效");
                return;
            } catch (Exception e) {
                sendErrorResponse(response, "INVALID_PROJECT", "项目配置加载失败");
                return;
            }

            if (projectConfig == null) {
                sendErrorResponse(response, "INVALID_PROJECT", "项目不存在");
                return;
            }
            if (!projectConfig.isActive()) {
                sendErrorResponse(response, "PROJECT_INACTIVE", "项目未激活");
                return;
            }

            // 3. 提取认证请求头
            String apiKey = request.getHeader("X-API-Key");
            String deviceId = request.getHeader("X-Device-ID");
            String userId = request.getHeader("X-User-ID");
            String timestamp = request.getHeader("X-Timestamp");
            String signature = request.getHeader("X-Signature");

            // 4. 验证必需字段
            if (apiKey == null || deviceId == null || userId == null ||
                    timestamp == null || signature == null) {
                sendErrorResponse(response, "MISSING_HEADERS", "缺少必需的请求头");
                return;
            }

            if (!CryptoUtils.isValidUUID(deviceId)) {
                sendErrorResponse(response, "INVALID_DEVICE_ID", "无效的设备ID格式");
                return;
            }

            if (!USER_ID_PATTERN.matcher(userId).matches()) {
                sendErrorResponse(response, "INVALID_USER_ID", "无效的用户ID格式");
                return;
            }

            // 5. 验证时间戳（防重放攻击）
            try {
                long requestTime = Long.parseLong(timestamp);
                long currentTime = System.currentTimeMillis();
                long timeDiff = Math.abs(currentTime - requestTime);
                
                // 允许5分钟的时间差
                if (timeDiff > signatureValidityMs) {
                    sendErrorResponse(response, "TIMESTAMP_EXPIRED", "请求时间戳已过期");
                    return;
                }
            } catch (NumberFormatException e) {
                sendErrorResponse(response, "INVALID_TIMESTAMP", "无效的时间戳格式");
                return;
            }

            // 6. 从项目数据库查询设备信息
            DataSource dataSource = dataSourceManager.getDataSource(projectId);
            String devicesTable = dataSourceManager.getTableName(projectId, "devices");
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            Device device = queryDevice(jdbcTemplate, devicesTable, apiKey, deviceId, projectId);

            if (device == null) {
                log.log(System.Logger.Level.WARNING, "认证失败: 无效的API Key或设备ID - {0}/{1}", projectId, deviceId);
                sendErrorResponse(response, "INVALID_CREDENTIALS", "无效的API Key或设备ID");
                return;
            }

            // 7. 检查设备是否被封禁
            if (Boolean.TRUE.equals(device.getIsBanned())) {
                log.log(System.Logger.Level.WARNING, "认证失败: 设备已被封禁 - {0}", deviceId);
                sendErrorResponse(response, "DEVICE_BANNED", "设备已被封禁");
                return;
            }

            // 8. 验证HMAC签名
            String signatureData = CryptoUtils.buildSignatureData(
                    request.getMethod(),
                    request.getRequestURI(),
                    timestamp,
                    deviceId,
                    userId,
                    "" // 为避免消费请求体，这里不参与签名；如需签名 body，可用缓存包装器读取。
            );

            if (!CryptoUtils.verifySignature(signatureData, signature, device.getSecretKey())) {
                log.log(System.Logger.Level.WARNING, "认证失败: 签名验证失败 - {0}", deviceId);
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

            // 10. 继续处理请求
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "认证过滤器异常", e);
            sendErrorResponse(response, "AUTH_ERROR", "认证失败");
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
                    "SELECT * FROM %s WHERE api_key = ? AND device_id = ?::uuid AND project_id = ?",
                    tableName
            );

            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Device device = new Device();
                device.setId(rs.getLong("id"));
                device.setDeviceId(UUID.fromString(rs.getString("device_id")));
                device.setApiKey(rs.getString("api_key"));
                device.setSecretKey(rs.getString("secret_key"));
                device.setDeviceModel(rs.getString("device_model"));
                device.setOsVersion(rs.getString("os_version"));
                device.setAppVersion(rs.getString("app_version"));
                device.setProjectId(rs.getString("project_id"));
                device.setIsBanned(rs.getBoolean("is_banned"));
                device.setBanReason(rs.getString("ban_reason"));
                device.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                device.setLastActiveAt(rs.getTimestamp("last_active_at").toInstant());
                return device;
            }, apiKey, deviceId, projectId);
        } catch (Exception e) {
            log.log(System.Logger.Level.DEBUG, "Device not found: {0}", deviceId);
            return null;
        }
    }

    /**
     * 判断是否为公开路径
     */
    private boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (path.startsWith(publicPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        
        ApiResponse<Void> apiResponse = ApiResponse.error(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
