package com.github.analyticshub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrationResult;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.database.project.ProjectSchemaStatus;
import com.github.analyticshub.dto.AdminProjectCreateRequest;
import com.github.analyticshub.dto.AdminProjectUpdateRequest;
import com.github.analyticshub.dto.ProjectConnectionTestResult;
import com.github.analyticshub.dto.ProjectHealthResult;
import com.github.analyticshub.dto.ProjectInitResult;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import com.github.analyticshub.security.ProjectCredentialCipher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 管理端项目服务
 */
@Service
public class AdminProjectService {

    private static final System.Logger log = System.getLogger(AdminProjectService.class.getName());

    private static final int DEFAULT_DB_PORT = 5432;
    private static final int MAX_PROJECT_ID_LENGTH = 50;
    private static final int MAX_TABLE_PREFIX_LENGTH = 40;
    private static final int MAX_DB_NAME_LENGTH = 63;
    private static final int MAX_SCHEMA_NAME_LENGTH = 63;
    private static final String DEFAULT_DB_SCHEMA = "analytics";

    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private final AnalyticsProjectMapper projectMapper;
    private final MultiDataSourceManager dataSourceManager;
    private final ProjectSchemaMigrator projectSchemaMigrator;
    private final ProjectCredentialCipher credentialCipher;


    public AdminProjectService(
            AnalyticsProjectMapper projectMapper,
            MultiDataSourceManager dataSourceManager,
            ProjectSchemaMigrator projectSchemaMigrator,
            ProjectCredentialCipher credentialCipher
    ) {
        this.projectMapper = projectMapper;
        this.dataSourceManager = dataSourceManager;
        this.projectSchemaMigrator = projectSchemaMigrator;
        this.credentialCipher = credentialCipher;
    }

    public List<AnalyticsProject> listProjects() {
        QueryWrapper<AnalyticsProject> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("created_at");
        return projectMapper.selectList(wrapper);
    }

    @Transactional
    public AnalyticsProject createProject(AdminProjectCreateRequest request) {
        String projectId = normalizeProjectId(request.projectId());
        String tablePrefix = normalizeTablePrefix(request.tablePrefix());
        int dbPort = normalizeDbPort(request.dbPort());
        String dbName = normalizeDbName(request.dbName());
        String dbSchema = normalizeDbSchema(request.dbSchema());

        AnalyticsProject existing = projectMapper.selectOne(
                new LambdaQueryWrapper<AnalyticsProject>().eq(AnalyticsProject::getProjectId, projectId)
        );
        if (existing != null) {
            throw new BusinessException("PROJECT_EXISTS", "项目已存在", HttpStatus.CONFLICT);
        }

        AnalyticsProject project = new AnalyticsProject();
        project.setProjectId(projectId);
        project.setProjectName(request.projectName());
        project.setDbHost(request.dbHost());
        project.setDbPort(dbPort);
        project.setDbName(dbName);
        project.setDbSchema(dbSchema);
        project.setDbUser(request.dbUser());
        project.setDbPasswordEncrypted(encryptProjectPassword(projectId, request.dbPassword()));
        project.setTablePrefix(tablePrefix);
        project.setIsActive(Boolean.TRUE);

        projectMapper.insert(project);

        invalidateProjectRuntimeAfterCommit(projectId);

        return projectMapper.selectById(project.getId());
    }

    @Transactional
    public AnalyticsProject updateProject(Long id, AdminProjectUpdateRequest request) {
        AnalyticsProject project = requireProject(id);

        if (request.projectName() != null) {
            project.setProjectName(request.projectName());
        }
        if (request.dbHost() != null) {
            project.setDbHost(request.dbHost());
        }
        if (request.dbPort() != null) {
            project.setDbPort(normalizeDbPort(request.dbPort()));
        }
        if (request.dbName() != null) {
            project.setDbName(normalizeDbName(request.dbName()));
        }
        if (request.dbSchema() != null) {
            project.setDbSchema(normalizeDbSchema(request.dbSchema()));
        }
        if (request.dbUser() != null) {
            project.setDbUser(request.dbUser());
        }
        if (request.tablePrefix() != null) {
            project.setTablePrefix(normalizeTablePrefix(request.tablePrefix()));
        }
        if (request.isActive() != null) {
            project.setIsActive(request.isActive());
        }
        if (request.dbPassword() != null && !request.dbPassword().isBlank()) {
            project.setDbPasswordEncrypted(encryptProjectPassword(project.getProjectId(), request.dbPassword()));
        }

        projectMapper.updateById(project);
        invalidateProjectRuntimeAfterCommit(project.getProjectId());

        return projectMapper.selectById(project.getId());
    }

    @Transactional
    public AnalyticsProject deleteProject(Long id) {
        AnalyticsProject project = requireProject(id);
        projectMapper.deleteById(id);
        invalidateProjectRuntimeAfterCommit(project.getProjectId());
        return project;
    }

    public ProjectConnectionTestResult testConnection(Long id) {
        ProjectDbConfig config = resolveProjectConfig(id);
        try (HikariDataSource dataSource = createDataSource(config)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ProjectConnectionTestResult("数据库连接成功");
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "测试项目数据库连接失败", e);
            throw new BusinessException(
                    "DB_CONNECTION_FAILED",
                    "无法连接项目数据库，请检查地址、凭据与网络",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    public ProjectInitResult initializeProjectDatabase(Long id) {
        ProjectDbConfig config = resolveProjectConfig(id);
        String schema = normalizeDbSchema(config.dbSchema());
        String prefix = normalizeTablePrefix(config.tablePrefix());

        try (HikariDataSource dataSource = createDataSource(config)) {
            try {
                new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
            } catch (Exception connectionException) {
                log.log(System.Logger.Level.WARNING, "初始化前连接项目数据库失败", connectionException);
                throw new BusinessException(
                        "DB_CONNECTION_FAILED",
                        "无法连接项目数据库，请检查地址、凭据与网络",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }

            try {
                ProjectSchemaMigrationResult migration = projectSchemaMigrator.migrate(dataSource, schema, prefix);
                String message = migration.migrationsExecuted() == 0
                        ? "项目 " + config.projectId() + " 数据库已是最新版本"
                        : "项目 " + config.projectId() + " 数据库迁移成功";
                return new ProjectInitResult(
                        message,
                        migration.tables(),
                        migration.currentVersion(),
                        migration.migrationsExecuted(),
                        migration.historyTable(),
                        migration.legacyBaselineApplied()
                );
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception migrationException) {
                log.log(System.Logger.Level.WARNING, "项目数据库迁移失败", migrationException);
                throw new BusinessException(
                        "PROJECT_SCHEMA_MIGRATION_FAILED",
                        "项目数据库迁移失败，请检查数据库权限和迁移状态",
                        HttpStatus.CONFLICT
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "创建项目数据库连接池失败", e);
            throw new BusinessException(
                    "DB_CONNECTION_FAILED",
                    "无法连接项目数据库，请检查地址、凭据与网络",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    public ProjectHealthResult checkProjectHealth(Long id) {
        ProjectDbConfig config = resolveProjectConfig(id);
        String schema = normalizeDbSchema(config.dbSchema());
        String prefix = normalizeTablePrefix(config.tablePrefix());

        try (HikariDataSource dataSource = createDataSource(config)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            try {
                ProjectSchemaStatus status = projectSchemaMigrator.inspect(dataSource, schema, prefix);
                return new ProjectHealthResult(
                        true,
                        status.tables(),
                        status.allTablesExist(),
                        status.current(),
                        status.migrationHistoryValid(),
                        status.currentVersion(),
                        status.pendingMigrations(),
                        status.historyTable(),
                        null,
                        null
                );
            } catch (Exception schemaException) {
                log.log(System.Logger.Level.WARNING, "检查项目数据库结构失败", schemaException);
                return new ProjectHealthResult(
                        true,
                        java.util.Map.of(),
                        false,
                        false,
                        false,
                        null,
                        0,
                        null,
                        "SCHEMA_INSPECTION_FAILED",
                        "数据库可连接，但结构检查失败"
                );
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "连接项目数据库失败", e);
            return new ProjectHealthResult(
                    false,
                    java.util.Map.of(),
                    false,
                    false,
                    false,
                    null,
                    0,
                    null,
                    "DB_CONNECTION_FAILED",
                    "无法连接项目数据库"
            );
        }
    }

    private AnalyticsProject requireProject(Long id) {
        AnalyticsProject project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException("PROJECT_NOT_FOUND", "项目不存在", HttpStatus.NOT_FOUND);
        }
        return project;
    }

    private void invalidateProjectRuntimeAfterCommit(String projectId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateProjectRuntime(projectId);
                }
            });
            return;
        }
        invalidateProjectRuntime(projectId);
    }

    private void invalidateProjectRuntime(String projectId) {
        try {
            dataSourceManager.reloadProject(projectId);
        } catch (RuntimeException exception) {
            // The system-database change is already committed at this point.
            // Keep the request successful and make the cache failure observable.
            log.log(System.Logger.Level.ERROR,
                    "项目运行时缓存清理失败，等待下一次显式重载: projectId={0}", projectId);
        }
    }

    private ProjectDbConfig resolveProjectConfig(Long id) {
        AnalyticsProject project = requireProject(id);
        String projectId = normalizeProjectId(project.getProjectId());
        String tablePrefix = normalizeTablePrefix(project.getTablePrefix());
        String dbSchema = normalizeDbSchema(project.getDbSchema());

        String password = null;
        if (project.getDbPasswordEncrypted() != null && !project.getDbPasswordEncrypted().isBlank()) {
            password = credentialCipher.decrypt(projectId, project.getDbPasswordEncrypted());
        }

        return new ProjectDbConfig(
                projectId,
                project.getDbHost(),
                normalizeDbPort(project.getDbPort()),
                normalizeDbName(project.getDbName()),
                dbSchema,
                project.getDbUser(),
                password,
                tablePrefix
        );
    }

    private static String normalizeProjectId(String projectId) {
        if (projectId == null || projectId.isBlank() || projectId.length() > MAX_PROJECT_ID_LENGTH) {
            throw new IllegalArgumentException("projectId 格式无效");
        }
        if (!PROJECT_ID_PATTERN.matcher(projectId).matches()) {
            throw new IllegalArgumentException("projectId 格式无效");
        }
        return projectId;
    }

    private String encryptProjectPassword(String projectId, String password) {
        if (!credentialCipher.isConfigured()) {
            throw new BusinessException(
                    "PROJECT_CREDENTIAL_ENCRYPTION_NOT_CONFIGURED",
                    "项目数据库凭据加密密钥未配置",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        return credentialCipher.encrypt(projectId, password);
    }

    private static String normalizeTablePrefix(String tablePrefix) {
        if (tablePrefix == null) {
            return "analytics_";
        }
        if (tablePrefix.isEmpty()) {
            return "";
        }
        if (tablePrefix.isBlank()) {
            throw new IllegalArgumentException("tablePrefix 格式无效");
        }
        if (tablePrefix.length() > MAX_TABLE_PREFIX_LENGTH) {
            throw new IllegalArgumentException("tablePrefix 长度超限");
        }
        if (!IDENTIFIER_PATTERN.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("tablePrefix 格式无效");
        }
        return tablePrefix;
    }

    private static int normalizeDbPort(Integer dbPort) {
        int port = dbPort == null ? DEFAULT_DB_PORT : dbPort;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("dbPort 无效");
        }
        return port;
    }

    private static String normalizeDbName(String dbName) {
        if (dbName == null || dbName.isBlank() || dbName.length() > MAX_DB_NAME_LENGTH) {
            throw new IllegalArgumentException("dbName 无效");
        }
        if (!IDENTIFIER_PATTERN.matcher(dbName).matches()) {
            throw new IllegalArgumentException("dbName 格式无效");
        }
        return dbName;
    }

    private static String normalizeDbSchema(String dbSchema) {
        if (dbSchema == null || dbSchema.isBlank()) {
            return DEFAULT_DB_SCHEMA;
        }
        if (dbSchema.length() > MAX_SCHEMA_NAME_LENGTH) {
            throw new IllegalArgumentException("dbSchema 长度超限");
        }
        if (!IDENTIFIER_PATTERN.matcher(dbSchema).matches()) {
            throw new IllegalArgumentException("dbSchema 格式无效");
        }
        return dbSchema;
    }

    private static HikariDataSource createDataSource(ProjectDbConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s?currentSchema=%s,public",
                config.dbHost(), config.dbPort(), config.dbName(), config.dbSchema()));
        hikariConfig.setUsername(config.dbUser());
        hikariConfig.setPassword(config.dbPassword());
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        // 管理端操作多为短链路，控制连接池规模避免占用过多资源。
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setConnectionTimeout(3000);
        hikariConfig.setValidationTimeout(2000);
        hikariConfig.setPoolName("AdminProject-" + config.projectId());
        return new HikariDataSource(hikariConfig);
    }

    private record ProjectDbConfig(
            String projectId,
            String dbHost,
            int dbPort,
            String dbName,
            String dbSchema,
            String dbUser,
            String dbPassword,
            String tablePrefix
    ) {}
}
