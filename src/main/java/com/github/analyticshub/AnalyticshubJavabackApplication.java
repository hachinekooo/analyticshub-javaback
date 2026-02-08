package com.github.analyticshub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import com.github.analyticshub.logging.StartupEnvironmentLogger;

/**
 * Analytics Hub - 多项目分析系统后端服务
 * Spring Boot 4.0.1 + JDK 25 + MyBatis Plus
 * 
 * 特性:
 * - 多项目支持，每个项目可配置独立数据库
 * - 采集端接口使用 API Key + HMAC 签名认证
 * - 管理端接口使用 Admin Token（X-Admin-Token / Bearer）认证
 * - 使用 Flyway 进行数据库版本管理
 * - 使用 HikariCP 进行高性能连接池管理
 * - 完全的 RESTful API 设计
 */
@SpringBootApplication
@MapperScan("com.github.analyticshub.mapper")
public class AnalyticshubJavabackApplication {

    private static final System.Logger log = System.getLogger(AnalyticshubJavabackApplication.class.getName());

    private final Environment environment;
    private final StartupEnvironmentLogger startupEnvironmentLogger;

    public AnalyticshubJavabackApplication(Environment environment,
                                           StartupEnvironmentLogger startupEnvironmentLogger) {
        this.environment = environment;
        this.startupEnvironmentLogger = startupEnvironmentLogger;
    }

    public static void main(String[] args) {
        SpringApplication.run(AnalyticshubJavabackApplication.class, args);
    }

    /**
     * 应用启动完成后的回调
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 启动完成后输出环境信息（敏感信息已打码）
        startupEnvironmentLogger.logStartupEnvironment();

        // 启动完成后集中打印关键地址，便于本地联调快速确认服务可用性。
        String port = environment.getProperty("server.port", "3001");
        String address = environment.getProperty("server.address");
        String profile = String.join(", ", environment.getActiveProfiles());
        
        log.log(System.Logger.Level.INFO, "");
        log.log(System.Logger.Level.INFO, "==================================================");
        log.log(System.Logger.Level.INFO, "✓ Analytics Hub 服务已启动");
        if (address == null || address.isBlank()) {
            log.log(System.Logger.Level.INFO, "  地址: 监听所有网卡 (server.address 未设置), 端口 {0}", port);
        } else {
            log.log(System.Logger.Level.INFO, "  地址: http://{0}:{1}", address, port);
        }
        log.log(System.Logger.Level.INFO, "  环境: {0}", profile.isEmpty() ? "default" : profile);
        log.log(System.Logger.Level.INFO, "  健康检查: http://localhost:{0}/api/health", port);
        log.log(System.Logger.Level.INFO, "  API 文档: http://localhost:{0}/actuator", port);
        log.log(System.Logger.Level.INFO, "==================================================");
        log.log(System.Logger.Level.INFO, "");
    }
}
