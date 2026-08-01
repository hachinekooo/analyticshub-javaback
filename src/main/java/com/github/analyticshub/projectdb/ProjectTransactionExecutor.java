package com.github.analyticshub.projectdb;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.Function;

/**
 * 在指定项目数据源上执行本地事务。
 *
 * <p>项目库是运行时动态选择的，不能依赖默认系统库的事务管理器。
 * 本执行器将同一回调中的所有 {@link JdbcTemplate} 操作绑定到同一项目连接，
 * 让幂等记录、业务事实和同步派生结果一起提交或一起回滚。</p>
 */
@Component
public class ProjectTransactionExecutor {

    public <T> T execute(DataSource dataSource, Function<JdbcTemplate, T> operation) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> operation.apply(new JdbcTemplate(dataSource)));
    }
}
