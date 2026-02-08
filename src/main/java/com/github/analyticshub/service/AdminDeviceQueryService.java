package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminDeviceRecord;
import com.github.analyticshub.dto.AdminDevicesResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端设备查询服务
 */
@Service
public class AdminDeviceQueryService {

    private final MultiDataSourceManager dataSourceManager;

    public AdminDeviceQueryService(MultiDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public AdminDevicesResponse listDevices(String projectId, String from, String to,
                                            Integer page, Integer pageSize,
                                            String deviceId, String apiKey, Boolean isBanned) {
        String normalizedProjectId = normalizeProjectId(projectId);
        AdminQueryUtils.Range range = AdminQueryUtils.resolveRange(from, to);
        AdminQueryUtils.Paging paging = AdminQueryUtils.resolvePaging(page, pageSize);
        ProjectContext context = requireProject(normalizedProjectId);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(context.dataSource());

        String devicesTable = dataSourceManager.getTableName(normalizedProjectId, "devices");

        if (deviceId != null && !deviceId.isBlank() && !CryptoUtils.isValidUUID(deviceId)) {
            throw new IllegalArgumentException("deviceId 格式无效");
        }

        StringBuilder where = new StringBuilder(" WHERE project_id = ? AND created_at >= ? AND created_at < ? ");
        List<Object> args = new ArrayList<>();
        args.add(normalizedProjectId);
        args.add(Timestamp.from(range.start()));
        args.add(Timestamp.from(range.end()));

        if (deviceId != null && !deviceId.isBlank()) {
            where.append(" AND device_id = ?::uuid ");
            args.add(deviceId.trim());
        }
        if (apiKey != null && !apiKey.isBlank()) {
            where.append(" AND api_key = ? ");
            args.add(apiKey.trim());
        }
        if (isBanned != null) {
            where.append(" AND is_banned = ? ");
            args.add(isBanned);
        }

        String countSql = String.format("SELECT COUNT(*) FROM %s %s", devicesTable, where);
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());
        long totalValue = total == null ? 0L : total;

        String listSql = String.format(
                "SELECT device_id, api_key, device_model, os_version, app_version, is_banned, ban_reason, created_at, last_active_at " +
                        "FROM %s %s ORDER BY created_at DESC LIMIT ? OFFSET ?",
                devicesTable,
                where
        );

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(paging.pageSize());
        listArgs.add(paging.offset());

        List<AdminDeviceRecord> items = jdbcTemplate.query(listSql, (rs, rowNum) ->
                        new AdminDeviceRecord(
                                rs.getString("device_id"),
                                rs.getString("api_key"),
                                rs.getString("device_model"),
                                rs.getString("os_version"),
                                rs.getString("app_version"),
                                rs.getBoolean("is_banned"),
                                rs.getString("ban_reason"),
                                rs.getTimestamp("created_at").toInstant().toString(),
                                rs.getTimestamp("last_active_at").toInstant().toString()
                        ),
                listArgs.toArray()
        );

        return new AdminDevicesResponse(
                normalizedProjectId,
                range.start().toString(),
                range.end().toString(),
                paging.page(),
                paging.pageSize(),
                totalValue,
                items
        );
    }

    private ProjectContext requireProject(String projectId) {
        String normalizedProjectId = normalizeProjectId(projectId);
        if (normalizedProjectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }

        MultiDataSourceManager.ProjectConfig projectConfig;
        try {
            projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        } catch (IllegalArgumentException e) {
            logDebug("Invalid projectId", normalizedProjectId, e);
            throw BusinessException.invalidProject(normalizedProjectId);
        } catch (Exception e) {
            logDebug("Failed to load project config", normalizedProjectId, e);
            throw BusinessException.invalidProject(normalizedProjectId);
        }

        if (projectConfig == null) {
            logDebug("Project config is null", normalizedProjectId, null);
            throw BusinessException.invalidProject(normalizedProjectId);
        }
        if (!Boolean.TRUE.equals(projectConfig.isActive())) {
            throw BusinessException.projectInactive();
        }

        try {
            DataSource dataSource = dataSourceManager.getDataSource(normalizedProjectId);
            return new ProjectContext(projectConfig, dataSource);
        } catch (Exception e) {
            logDebug("Project datasource unavailable", normalizedProjectId, e);
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }
    }

    private record ProjectContext(MultiDataSourceManager.ProjectConfig config, DataSource dataSource) {}

    private static String normalizeProjectId(String projectId) {
        if (projectId == null) {
            return "";
        }
        String stripped = projectId.strip();
        StringBuilder builder = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT) {
                continue;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private static void logDebug(String message, String projectId, Exception e) {
        System.Logger logger = System.getLogger(AdminDeviceQueryService.class.getName());
        if (e == null) {
            logger.log(System.Logger.Level.DEBUG, "{0}: projectId={1}", message, debugValue(projectId));
        } else {
            logger.log(System.Logger.Level.DEBUG, "{0}: projectId={1}, error={2}", message, debugValue(projectId), e.getMessage());
        }
    }

    private static String debugValue(String value) {
        if (value == null) {
            return "len=0 hex=<null>";
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%04x", (int) value.charAt(i)));
        }
        return "len=" + value.length() + " hex=" + hex;
    }
}
