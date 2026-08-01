package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminDeviceCredentialResetResponse;
import com.github.analyticshub.dto.DeviceRegisterRequest;
import com.github.analyticshub.dto.DeviceRegisterResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import com.github.analyticshub.security.RequestContext;
import com.github.analyticshub.util.CryptoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 认证服务
 * 处理设备注册和认证逻辑
 */
@Service
public class AuthService {

    private static final System.Logger log = System.getLogger(AuthService.class.getName());

    private final MultiDataSourceManager dataSourceManager;
    private final ProjectTransactionExecutor projectTransactions;
    private final boolean allowInsecureReregistration;
    private final long credentialRotationGraceSeconds;

    public AuthService(
            MultiDataSourceManager dataSourceManager,
            ProjectTransactionExecutor projectTransactions,
            @Value("${app.security.allow-insecure-device-reregistration:false}")
            boolean allowInsecureReregistration,
            @Value("${app.security.credential-rotation-grace-seconds:600}")
            long credentialRotationGraceSeconds
    ) {
        if (credentialRotationGraceSeconds < 1 || credentialRotationGraceSeconds > 86_400) {
            throw new IllegalArgumentException(
                    "app.security.credential-rotation-grace-seconds 必须在 1 到 86400 之间"
            );
        }
        this.dataSourceManager = dataSourceManager;
        this.projectTransactions = projectTransactions;
        this.allowInsecureReregistration = allowInsecureReregistration;
        this.credentialRotationGraceSeconds = credentialRotationGraceSeconds;
    }

    /**
     * 设备注册
     * 默认只允许首次注册；已有设备必须通过受认证的轮换接口更新凭证。
     */
    public DeviceRegisterResponse registerDevice(String projectId, DeviceRegisterRequest request) {
        // 1. 验证项目
        MultiDataSourceManager.ProjectConfig projectConfig = dataSourceManager.getProjectConfig(projectId);
        if (projectConfig == null || !projectConfig.isActive()) {
            throw BusinessException.invalidProject(projectId);
        }

        // 2. 验证UUID格式
        if (!CryptoUtils.isValidUUID(request.deviceId())) {
            throw BusinessException.invalidDeviceId();
        }

        // 3. 获取项目数据源
        var dataSource = dataSourceManager.getDataSource(projectId);
        String devicesTable = dataSourceManager.getTableName(projectId, "devices");

        // 4. 为首次注册发放完整签名凭证。公开注册接口绝不能默认覆盖已有凭证。
        String apiKey = CryptoUtils.generateApiKey();
        String secretKey = CryptoUtils.generateSecretKey();

        return projectTransactions.execute(dataSource, jdbcTemplate -> {
            String checkSql = String.format(
                    "SELECT id FROM %s WHERE device_id = ?::uuid AND project_id = ? FOR UPDATE",
                    devicesTable
            );
            boolean existingDevice = !jdbcTemplate.queryForList(
                    checkSql,
                    Long.class,
                    request.deviceId(),
                    projectId
            ).isEmpty();

            if (existingDevice) {
                if (!allowInsecureReregistration) {
                    throw BusinessException.deviceAlreadyRegistered();
                }
                String updateSql = String.format(
                        "UPDATE %s SET api_key = ?, secret_key = ?, previous_api_key = NULL, " +
                                "previous_secret_key = NULL, previous_credentials_expires_at = NULL, " +
                                "device_model = ?, os_version = ?, app_version = ?, last_active_at = ? " +
                                "WHERE device_id = ?::uuid AND project_id = ?",
                        devicesTable
                );
                jdbcTemplate.update(
                        updateSql,
                        apiKey,
                        secretKey,
                        request.deviceModel(),
                        request.osVersion(),
                        request.appVersion(),
                        Timestamp.from(Instant.now()),
                        request.deviceId(),
                        projectId
                );
                log.log(System.Logger.Level.WARNING,
                        "已通过显式兼容开关重新发放设备凭证: projectId={0}", projectId);
                return new DeviceRegisterResponse(apiKey, secretKey, false);
            }

            String insertSql = String.format(
                    "INSERT INTO %s (device_id, api_key, secret_key, device_model, os_version, app_version, project_id, created_at, last_active_at) " +
                            "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (project_id, device_id) DO NOTHING",
                    devicesTable
            );

            Instant now = Instant.now();
            int inserted = jdbcTemplate.update(
                    insertSql,
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
            if (inserted != 1) {
                // Concurrent first registration lost the unique-key race. Never
                // rotate the winner's credentials from this public endpoint.
                throw BusinessException.deviceAlreadyRegistered();
            }

            log.log(System.Logger.Level.INFO, "新设备注册成功: projectId={0}", projectId);
            return new DeviceRegisterResponse(apiKey, secretKey, true);
        });
    }

    /**
     * Rotates credentials only after the current credentials passed the HMAC
     * authentication filter. This is the normal credential-renewal path.
     */
    public DeviceRegisterResponse rotateCredentials() {
        RequestContext context = RequestContext.get();
        if (context.getProjectId() == null
                || context.getDevice() == null
                || context.getDevice().getDeviceId() == null
                || context.getDevice().getApiKey() == null
                || context.getDataSource() == null) {
            throw new BusinessException("UNAUTHORIZED", "请求未认证", HttpStatus.UNAUTHORIZED);
        }

        String projectId = context.getProjectId();
        String devicesTable = dataSourceManager.getTableName(projectId, "devices");
        return projectTransactions.execute(context.getDataSource(), jdbcTemplate -> {
            String selectSql = String.format(
                    "SELECT api_key, secret_key, previous_api_key, previous_credentials_expires_at " +
                            "FROM %s WHERE project_id = ? AND device_id = ?::uuid FOR UPDATE",
                    devicesTable
            );
            var states = jdbcTemplate.query(
                    selectSql,
                    (resultSet, rowNumber) -> new CredentialRotationState(
                            resultSet.getString("api_key"),
                            resultSet.getString("secret_key"),
                            resultSet.getString("previous_api_key"),
                            resultSet.getTimestamp("previous_credentials_expires_at") == null
                                    ? null
                                    : resultSet.getTimestamp("previous_credentials_expires_at").toInstant()
                    ),
                    projectId,
                    context.getDevice().getDeviceId().toString()
            );
            if (states.isEmpty()) {
                throw credentialRotationConflict();
            }
            CredentialRotationState state = states.getFirst();

            Instant now = Instant.now();
            String authenticatedApiKey = context.getDevice().getApiKey();
            if (authenticatedApiKey.equals(state.previousApiKey())
                    && state.previousExpiresAt() != null
                    && state.previousExpiresAt().isAfter(now)) {
                // The first response may have been lost. Returning the already
                // active pair makes a retry idempotent during the grace window.
                return new DeviceRegisterResponse(state.currentApiKey(), state.currentSecretKey(), false);
            }
            if (!authenticatedApiKey.equals(state.currentApiKey())) {
                throw credentialRotationConflict();
            }

            String apiKey = CryptoUtils.generateApiKey();
            String secretKey = CryptoUtils.generateSecretKey();
            String updateSql = String.format(
                    "UPDATE %s SET previous_api_key = api_key, previous_secret_key = secret_key, " +
                            "previous_credentials_expires_at = ?, api_key = ?, secret_key = ?, last_active_at = ? " +
                            "WHERE project_id = ? AND device_id = ?::uuid AND api_key = ?",
                    devicesTable
            );
            int updated = jdbcTemplate.update(
                    updateSql,
                    Timestamp.from(now.plus(credentialRotationGraceSeconds, ChronoUnit.SECONDS)),
                    apiKey,
                    secretKey,
                    Timestamp.from(now),
                    projectId,
                    context.getDevice().getDeviceId().toString(),
                    authenticatedApiKey
            );
            if (updated != 1) {
                throw credentialRotationConflict();
            }
            log.log(System.Logger.Level.INFO, "设备凭证已通过认证链路轮换: projectId={0}", projectId);
            return new DeviceRegisterResponse(apiKey, secretKey, false);
        });
    }

    /**
     * Reissues a device credential pair through an administrator-authenticated
     * recovery path. The row lock makes reset atomic with client rotation, and
     * clearing the previous pair revokes every credential issued before reset.
     */
    public AdminDeviceCredentialResetResponse resetCredentialsByAdmin(String projectId, String deviceId) {
        String normalizedProjectId = projectId == null ? "" : projectId.strip();
        MultiDataSourceManager.ProjectConfig projectConfig;
        try {
            projectConfig = dataSourceManager.getProjectConfig(normalizedProjectId);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.invalidProject(normalizedProjectId);
        }
        if (projectConfig == null) {
            throw BusinessException.projectNotFound();
        }
        if (!projectConfig.isActive()) {
            throw BusinessException.projectInactive();
        }
        if (!CryptoUtils.isValidUUID(deviceId)) {
            throw BusinessException.invalidDeviceId();
        }

        String normalizedDeviceId = UUID.fromString(deviceId).toString();
        final DataSource dataSource;
        final String devicesTable;
        try {
            dataSource = dataSourceManager.getDataSource(normalizedProjectId);
            devicesTable = dataSourceManager.getTableName(normalizedProjectId, "devices");
        } catch (Exception exception) {
            throw BusinessException.projectDbUnavailable(normalizedProjectId);
        }

        return projectTransactions.execute(dataSource, jdbcTemplate -> {
            String lockSql = String.format(
                    "SELECT id FROM %s WHERE project_id = ? AND device_id = ?::uuid FOR UPDATE",
                    devicesTable
            );
            var deviceRows = jdbcTemplate.queryForList(
                    lockSql,
                    Long.class,
                    normalizedProjectId,
                    normalizedDeviceId
            );
            if (deviceRows.isEmpty()) {
                throw BusinessException.deviceNotFound();
            }

            String apiKey = CryptoUtils.generateApiKey();
            String secretKey = CryptoUtils.generateSecretKey();
            String updateSql = String.format(
                    "UPDATE %s SET api_key = ?, secret_key = ?, previous_api_key = NULL, " +
                            "previous_secret_key = NULL, previous_credentials_expires_at = NULL " +
                            "WHERE id = ? AND project_id = ?",
                    devicesTable
            );
            int updated = jdbcTemplate.update(
                    updateSql,
                    apiKey,
                    secretKey,
                    deviceRows.getFirst(),
                    normalizedProjectId
            );
            if (updated != 1) {
                throw BusinessException.deviceNotFound();
            }

            log.log(System.Logger.Level.WARNING,
                    "管理员已重置设备凭据，旧凭据已立即失效: projectId={0}",
                    normalizedProjectId);
            return new AdminDeviceCredentialResetResponse(
                    normalizedProjectId,
                    normalizedDeviceId,
                    apiKey,
                    secretKey
            );
        });
    }

    private static BusinessException credentialRotationConflict() {
        return new BusinessException(
                "CREDENTIAL_ROTATION_FAILED",
                "设备凭证已变化，请重新认证",
                HttpStatus.CONFLICT
        );
    }

    private record CredentialRotationState(
            String currentApiKey,
            String currentSecretKey,
            String previousApiKey,
            Instant previousExpiresAt
    ) {}
}
