package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.DeviceRegisterRequest;
import com.github.analyticshub.dto.DeviceRegisterResponse;
import com.github.analyticshub.entity.Device;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 认证服务
 * 处理设备注册和认证逻辑
 */
@Service
public class AuthService {

    private static final System.Logger log = System.getLogger(AuthService.class.getName());

    private final MultiDataSourceManager dataSourceManager;

    public AuthService(MultiDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * 设备注册
     * 如果设备已存在则返回现有密钥，否则创建新设备
     */
    @Transactional
    public DeviceRegisterResponse registerDevice(String projectId, DeviceRegisterRequest request) {
        // 1. 验证项目
        MultiDataSourceManager.ProjectConfig projectConfig = dataSourceManager.getProjectConfig(projectId);
        if (projectConfig == null || !projectConfig.isActive()) {
            throw BusinessException.invalidProject(projectId);
        }

        // 2. 验证UUID格式
        UUID deviceUuid;
        try {
            deviceUuid = UUID.fromString(request.deviceId());
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidDeviceId();
        }

        // 3. 获取项目数据源
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSourceManager.getDataSource(projectId));
        String devicesTable = dataSourceManager.getTableName(projectId, "devices");

        // 4. 检查设备是否已注册
        String checkSql = String.format(
                "SELECT api_key FROM %s WHERE device_id = ?::uuid AND project_id = ?",
                devicesTable
        );

        try {
            Device existingDevice = jdbcTemplate.queryForObject(checkSql, (rs, rowNum) -> {
                Device device = new Device();
                device.setApiKey(rs.getString("api_key"));
                return device;
            }, request.deviceId(), projectId);

            if (existingDevice != null) {
                // Idempotent registration: return existing API key without rotating secrets.
                log.log(System.Logger.Level.INFO, "设备已注册: {0}/{1}", projectId, request.deviceId());
                return new DeviceRegisterResponse(existingDevice.getApiKey(), null, false);
            }
        } catch (Exception e) {
            // 设备不存在，继续注册流程
            log.log(System.Logger.Level.DEBUG, "设备不存在，开始注册: {0}", request.deviceId());
        }

        // 5. 生成密钥
        String apiKey = CryptoUtils.generateApiKey();
        String secretKey = CryptoUtils.generateSecretKey();

        // 6. 插入新设备
        String insertSql = String.format(
                "INSERT INTO %s (device_id, api_key, secret_key, device_model, os_version, app_version, project_id, created_at, last_active_at) " +
                        "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)",
                devicesTable
        );

        Instant now = Instant.now();
        jdbcTemplate.update(insertSql,
                request.deviceId(),
                apiKey,
                secretKey,
                request.deviceModel(),
                request.osVersion(),
                request.appVersion(),
                projectId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        log.log(System.Logger.Level.INFO, "新设备注册成功: {0}/{1}", projectId, request.deviceId());

        return new DeviceRegisterResponse(apiKey, secretKey, true);
    }
}
