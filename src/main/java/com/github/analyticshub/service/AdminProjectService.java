package com.github.analyticshub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.AdminProjectCreateRequest;
import com.github.analyticshub.dto.AdminProjectUpdateRequest;
import com.github.analyticshub.dto.ProjectConnectionTestResult;
import com.github.analyticshub.dto.ProjectHealthResult;
import com.github.analyticshub.dto.ProjectInitResult;
import com.github.analyticshub.entity.AnalyticsProject;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import com.github.analyticshub.util.CryptoUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private final AnalyticsProjectMapper projectMapper;
    private final MultiDataSourceManager dataSourceManager;

    @Value("${spring.datasource.password:}")
    private String systemDbPassword;

    public AdminProjectService(AnalyticsProjectMapper projectMapper, MultiDataSourceManager dataSourceManager) {
        this.projectMapper = projectMapper;
        this.dataSourceManager = dataSourceManager;
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
        project.setDbUser(request.dbUser());
        project.setDbPasswordEncrypted(CryptoUtils.encrypt(request.dbPassword()));
        project.setTablePrefix(tablePrefix);
        project.setIsActive(Boolean.TRUE);

        projectMapper.insert(project);

        dataSourceManager.reloadProject(projectId);

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
            project.setDbPasswordEncrypted(CryptoUtils.encrypt(request.dbPassword()));
        }

        projectMapper.updateById(project);
        dataSourceManager.reloadProject(project.getProjectId());

        return projectMapper.selectById(project.getId());
    }

    @Transactional
    public AnalyticsProject deleteProject(Long id) {
        AnalyticsProject project = requireProject(id);
        projectMapper.deleteById(id);
        dataSourceManager.reloadProject(project.getProjectId());
        return project;
    }

    public ProjectConnectionTestResult testConnection(Long id) {
        ProjectDbConfig config = resolveProjectConfig(id);
        try (HikariDataSource dataSource = createDataSource(config)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ProjectConnectionTestResult("数据库连接成功");
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "测试连接失败: {0}", e.getMessage());
            throw new BusinessException("DB_CONNECTION_FAILED", "连接失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public ProjectInitResult initializeProjectDatabase(Long id) {
        ProjectDbConfig config = resolveProjectConfig(id);
        String prefix = normalizeTablePrefix(config.tablePrefix());
        String sql = loadProjectInitSql(prefix);

        try (HikariDataSource dataSource = createDataSource(config);
             Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "初始化数据库失败: {0}", e.getMessage());
            throw new BusinessException("PROJECT_INIT_FAILED", "初始化失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        List<String> tables = List.of(
                prefix + "devices",
                prefix + "events",
                prefix + "sessions",
                prefix + "traffic_metrics"
        );

        return new ProjectInitResult("项目 " + config.projectId() + " 数据库初始化成功", tables);
    }

    public ProjectHealthResult checkProjectHealth(Long id) {
        ProjectDbConfig config = resolveProjectConfig(id);
        String prefix = normalizeTablePrefix(config.tablePrefix());
        List<String> requiredTables = List.of("devices", "events", "sessions", "traffic_metrics");
        Map<String, Boolean> tables = new LinkedHashMap<>();

        try (HikariDataSource dataSource = createDataSource(config)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            for (String table : requiredTables) {
                String fullTableName = prefix + table;
                Boolean exists = jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?)",
                        Boolean.class,
                        fullTableName
                );
                tables.put(table, Boolean.TRUE.equals(exists));
            }

            boolean allTablesExist = tables.values().stream().allMatch(Boolean::booleanValue);
            return new ProjectHealthResult(true, tables, allTablesExist, null);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "检查项目健康失败: {0}", e.getMessage());
            return new ProjectHealthResult(false, Map.of(), false, e.getMessage());
        }
    }

    private AnalyticsProject requireProject(Long id) {
        AnalyticsProject project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException("PROJECT_NOT_FOUND", "项目不存在", HttpStatus.NOT_FOUND);
        }
        return project;
    }

    private ProjectDbConfig resolveProjectConfig(Long id) {
        AnalyticsProject project = requireProject(id);
        String projectId = normalizeProjectId(project.getProjectId());
        String tablePrefix = normalizeTablePrefix(project.getTablePrefix());

        String password = null;
        if (project.getDbPasswordEncrypted() != null && !project.getDbPasswordEncrypted().isBlank()) {
            password = CryptoUtils.decrypt(project.getDbPasswordEncrypted());
        }
        if ((password == null || password.isBlank()) && Objects.equals(projectId, "analytics-system")) {
            password = systemDbPassword;
        }

        return new ProjectDbConfig(
                projectId,
                project.getDbHost(),
                normalizeDbPort(project.getDbPort()),
                normalizeDbName(project.getDbName()),
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

    private static String normalizeTablePrefix(String tablePrefix) {
        if (tablePrefix == null || tablePrefix.isBlank()) {
            return "analytics_";
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

    private static String loadProjectInitSql(String prefix) {
        try {
            ClassPathResource resource = new ClassPathResource("db/project-init.sql");
            String sql;
            try (var inputStream = resource.getInputStream()) {
                sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            return sql.replace("{{PREFIX}}", prefix);
        } catch (Exception e) {
            throw new BusinessException("PROJECT_INIT_TEMPLATE_MISSING", "加载初始化脚本失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static HikariDataSource createDataSource(ProjectDbConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                config.dbHost(), config.dbPort(), config.dbName()));
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
            String dbUser,
            String dbPassword,
            String tablePrefix
    ) {}
}
