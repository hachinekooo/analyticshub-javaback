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
        return execute(dataSource, false, null, operation);
    }

    /**
     * 在项目库的只读事务中执行有时限的查询。
     *
     * <p>Spring 会把事务超时应用到同一连接上的 JDBC Statement，避免通过连接级
     * {@code SET statement_timeout} 污染连接池中的后续请求。</p>
     */
    public <T> T executeReadOnly(
            DataSource dataSource,
            int timeoutSeconds,
            Function<JdbcTemplate, T> operation
    ) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        return execute(dataSource, true, timeoutSeconds, operation);
    }

    private <T> T execute(
            DataSource dataSource,
            boolean readOnly,
            Integer timeoutSeconds,
            Function<JdbcTemplate, T> operation
    ) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setReadOnly(readOnly);
        if (timeoutSeconds != null) {
            transactionTemplate.setTimeout(timeoutSeconds);
        }
        return transactionTemplate.execute(status -> operation.apply(new JdbcTemplate(dataSource)));
    }
}
