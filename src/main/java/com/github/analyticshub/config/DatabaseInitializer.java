package com.github.analyticshub.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 系统数据库初始化与自检。
 *
 * 说明：系统库只承载项目配置（analytics_projects），
 * 不承载业务采集数据表。
 */
@Component
public class DatabaseInitializer {

    private static final System.Logger log = System.getLogger(DatabaseInitializer.class.getName());

    private final DataSource dataSource;
    private final AnalyticsProjectMapper projectMapper;

    @Value("${spring.flyway.enabled:true}")
    private boolean flywayEnabled;

    @Value("${spring.flyway.default-schema:analytics}")
    private String flywaySchema;

    public DatabaseInitializer(DataSource dataSource, AnalyticsProjectMapper projectMapper) {
        this.dataSource = dataSource;
        this.projectMapper = projectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void onApplicationReady() {
        try {
            if (!flywayEnabled) {
                return;
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .defaultSchema(flywaySchema)
                    .schemas(flywaySchema)
                    .table("flyway_schema_history")
                    .load();
            flyway.migrate();

            Long projectCount = projectMapper.selectCount(new QueryWrapper<>());

            log.log(System.Logger.Level.INFO, "✓ 系统数据库初始化检查完成");
            log.log(System.Logger.Level.INFO, "  - 项目配置: {0} 条记录", projectCount);

        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "✗ 系统数据库初始化检查失败", e);
            log.log(System.Logger.Level.ERROR, "  请确保系统数据库已创建并且 Flyway 迁移已执行");
        }
    }
}
