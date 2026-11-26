package com.github.analyticshub.extension.dashboard;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 看板组件的构建期扩展接口。扩展必须由后端部署方显式注册，并负责校验自己声明的配置项。
 */
public interface DashboardWidgetExtension {

    /**
     * 稳定的组件类型；扩展类型使用 {@code custom.} 前缀。
     */
    String type();

    /**
     * 扩展允许的顶层配置字段；通用的 {@code title} 由底座处理，不应重复声明。
     */
    Set<String> allowedConfigFields();

    /**
     * 是否要求传配置对象；要求时由通用校验先检查，再调用扩展校验。
     */
    default boolean configRequired() {
        return false;
    }

    /**
     * 校验扩展字段的值和结构；不符合要求时抛出 {@link IllegalArgumentException}。
     */
    void validateConfig(JsonNode config);
}
