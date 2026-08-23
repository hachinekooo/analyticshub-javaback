package com.github.analyticshub.security;

import com.github.analyticshub.entity.Device;

import javax.sql.DataSource;

/**
 * 请求上下文
 * 存储当前请求的项目信息、设备信息和数据库连接
 * 使用ThreadLocal确保线程安全
 */
public class RequestContext {
    
    private String projectId;
    private Device device;
    private String userId;
    private String idempotencyActorId;
    private DataSource dataSource;
    private String tablePrefix;

    private static final ThreadLocal<RequestContext> CONTEXT = ThreadLocal.withInitial(RequestContext::new);

    public static RequestContext get() {
        return CONTEXT.get();
    }

    public static void set(RequestContext context) {
        CONTEXT.set(context);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    // Getters and Setters
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 返回参与事件幂等摘要的 actor 原始表达。
     *
     * <p>认证链路会保留已验签的 Header 文本，避免服务升级时仅因 UUID
     * 大小写规范化而改变既有幂等键。事件存储与查询仍使用 {@link #userId}
     * 的规范化表达。</p>
     */
    public String getIdempotencyActorId() {
        return idempotencyActorId;
    }

    public void setIdempotencyActorId(String idempotencyActorId) {
        this.idempotencyActorId = idempotencyActorId;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }
}
