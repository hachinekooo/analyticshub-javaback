package com.github.analyticshub.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import com.github.analyticshub.util.CryptoUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 多数据源管理器
 * 管理多个项目的数据库连接池
 * 使用HikariCP实现高性能连接池
 */
@Component
public class MultiDataSourceManager {

    private static final System.Logger log = System.getLogger(MultiDataSourceManager.class.getName());

    private static final int MAX_DB_NAME_LENGTH = 63;
    private static final int MAX_SCHEMA_NAME_LENGTH = 63;
    private static final int MAX_TABLE_NAME_LENGTH = 63;
    private static final int MAX_TABLE_PREFIX_LENGTH = 40;
    private static final int MAX_PROJECT_ID_LENGTH = 50;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_]+$");
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    private final AnalyticsProjectMapper projectMapper;
    

    // 数据库连接池缓存
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    
    // 项目配置缓存
    private final Map<String, ProjectConfig> projectConfigs = new ConcurrentHashMap<>();

    public MultiDataSourceManager(AnalyticsProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    /**
     * 获取项目的数据源
     */
    public DataSource getDataSource(String projectId) {
        // Lazy init per project to avoid creating pools for unused projects.
        return dataSources.computeIfAbsent(projectId, this::createDataSource);
    }

    /**
     * 获取项目配置
     */
    public ProjectConfig getProjectConfig(String projectId) {
        // computeIfAbsent will cache the loaded config; do not manually put inside loadProjectConfig.
        return projectConfigs.computeIfAbsent(projectId, this::loadProjectConfig);
    }

    /**
     * 获取表名（带项目前缀）
     */
    public String getTableName(String projectId, String baseName) {
        ProjectConfig config = getProjectConfig(projectId);
        if (config == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        // 动态表名必须严格校验并安全拼接，避免标识符注入。
        validateProjectId(projectId);
        validateIdentifier(baseName, "base table name", MAX_TABLE_NAME_LENGTH);
        validateIdentifier(config.dbSchema(), "database schema", MAX_SCHEMA_NAME_LENGTH);
        validateTablePrefix(config.tablePrefix());

        String tableName = config.tablePrefix() + baseName;
        validateIdentifier(tableName, "table name", MAX_TABLE_NAME_LENGTH);
        return quoteIdentifier(tableName);
    }

    /**
     * 创建数据源
     */
    private HikariDataSource createDataSource(String projectId) {
        validateProjectId(projectId);

        ProjectConfig config = loadProjectConfig(projectId);
        if (config == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }

        if (!config.isActive()) {
            throw new IllegalStateException("Project is not active: " + projectId);
        }

        validateIdentifier(config.dbName(), "database name", MAX_DB_NAME_LENGTH);
        validateIdentifier(config.dbSchema(), "database schema", MAX_SCHEMA_NAME_LENGTH);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s?currentSchema=%s,public",
                config.dbHost(), config.dbPort(), config.dbName(), config.dbSchema()));
        hikariConfig.setUsername(config.dbUser());
        hikariConfig.setPassword(config.dbPassword());
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        
        // HikariCP 优化配置
        hikariConfig.setMaximumPoolSize(20);
        hikariConfig.setMinimumIdle(5);
        hikariConfig.setIdleTimeout(30000);
        hikariConfig.setConnectionTimeout(2000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setPoolName("AnalyticsPool-" + projectId);

        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        log.log(System.Logger.Level.INFO, "✓ Created datasource for project: {0}", projectId);
        
        return dataSource;
    }

    /**
     * 加载项目配置
     */
    private ProjectConfig loadProjectConfig(String projectId) {
        validateProjectId(projectId);

        LambdaQueryWrapper<AnalyticsProject> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AnalyticsProject::getProjectId, projectId);
        AnalyticsProject project = projectMapper.selectOne(queryWrapper);
        
        if (project == null) {
            log.log(System.Logger.Level.WARNING, "Project not found: {0} ({1})",
                    projectId,
                    debugValue(projectId));
            try {
                String available = projectMapper.selectList(new LambdaQueryWrapper<>())
                        .stream()
                        .map(AnalyticsProject::getProjectId)
                        .filter(v -> v != null && !v.isBlank())
                        .sorted()
                        .reduce((a, b) -> a + "," + b)
                        .orElse("<empty>");
                log.log(System.Logger.Level.DEBUG, "Available projects: {0}", available);
            } catch (Exception e) {
                log.log(System.Logger.Level.DEBUG, "Failed to load available projects", e);
            }
            return null;
        }

        String password = null;
        String encrypted = project.getDbPasswordEncrypted();
        if (encrypted != null && !encrypted.isBlank()) {
            password = CryptoUtils.decrypt(encrypted);
        }

        try {
            validateProjectId(project.getProjectId());
            validateIdentifier(project.getDbName(), "database name", MAX_DB_NAME_LENGTH);
            validateIdentifier(project.getDbSchema(), "database schema", MAX_SCHEMA_NAME_LENGTH);
            validateTablePrefix(project.getTablePrefix());
        } catch (IllegalArgumentException e) {
            log.log(System.Logger.Level.WARNING,
                    "Invalid project config: projectId={0}, dbName={1}, tablePrefix={2}, dbUser={3}",
                    debugValue(project.getProjectId()),
                    debugValue(project.getDbName()),
                    debugValue(project.getTablePrefix()),
                    debugValue(project.getDbUser()));
            throw e;
        }

        return new ProjectConfig(
                project.getProjectId(),
                project.getProjectName(),
                project.getDbHost(),
                project.getDbPort(),
                project.getDbName(),
                project.getDbSchema(),
                project.getDbUser(),
                password,
                project.getTablePrefix(),
                project.getIsActive()
        );
        // computeIfAbsent will cache the result, avoid recursive update here.
    }

    private static void validateProjectId(String projectId) {
        String normalized = projectId == null ? null : projectId.strip();
        if (normalized == null || normalized.isBlank() || normalized.length() > MAX_PROJECT_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid projectId");
        }
        if (!PROJECT_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid projectId");
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

    private static void validateTablePrefix(String tablePrefix) {
        if (tablePrefix == null) {
            throw new IllegalArgumentException("Invalid table prefix");
        }
        if (tablePrefix.isBlank()) {
            return;
        }
        if (tablePrefix.length() > MAX_TABLE_PREFIX_LENGTH) {
            throw new IllegalArgumentException("Invalid table prefix");
        }
        validateIdentifier(tablePrefix, "table prefix", MAX_TABLE_PREFIX_LENGTH);
    }

    private static void validateIdentifier(String identifier, String type, int maxLength) {
        if (identifier == null || identifier.isBlank() || identifier.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + type);
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid " + type);
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * 重新加载项目配置
     */
    public void reloadProject(String projectId) {
        // 清除缓存
        projectConfigs.remove(projectId);
        
        // 关闭旧连接池
        HikariDataSource oldDataSource = dataSources.remove(projectId);
        if (oldDataSource != null) {
            oldDataSource.close();
            log.log(System.Logger.Level.INFO, "Closed datasource for project: {0}", projectId);
        }
    }

    /**
     * 关闭所有数据源
     */
    @PreDestroy
    public void closeAll() {
        dataSources.values().forEach(ds -> {
            try {
                ds.close();
            } catch (Exception e) {
                log.log(System.Logger.Level.ERROR, "Failed to close datasource", e);
            }
        });
        dataSources.clear();
        projectConfigs.clear();
        log.log(System.Logger.Level.INFO, "All datasources closed");
    }

    /**
     * 项目配置记录
     * 使用JDK 25的record特性
     */
    public record ProjectConfig(
            String projectId,
            String projectName,
            String dbHost,
            Integer dbPort,
            String dbName,
            String dbSchema,
            String dbUser,
            String dbPassword,
            String tablePrefix,
            Boolean isActive
    ) {}
}
