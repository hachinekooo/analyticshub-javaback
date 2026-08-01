package com.github.analyticshub.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.analyticshub.mapper.AnalyticsProjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 系统数据库初始化与自检。
 *
 * 说明：系统库只承载项目配置（analytics_projects），
 * 不承载业务采集数据表。
 */
@Component
@Order(1)
public class DatabaseInitializer implements ApplicationRunner {

    private static final System.Logger log = System.getLogger(DatabaseInitializer.class.getName());

    private final AnalyticsProjectMapper projectMapper;

    public DatabaseInitializer(AnalyticsProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Flyway 由 Spring Boot 在 ApplicationRunner 之前统一执行。
        // 即使迁移被显式关闭，这里仍验证关键表；失败必须阻止应用进入可服务状态。
        Long projectCount = projectMapper.selectCount(new QueryWrapper<>());
        log.log(System.Logger.Level.INFO, "✓ 系统数据库迁移与启动自检完成");
        log.log(System.Logger.Level.INFO, "  - 项目配置: {0} 条记录", projectCount);
    }
}
