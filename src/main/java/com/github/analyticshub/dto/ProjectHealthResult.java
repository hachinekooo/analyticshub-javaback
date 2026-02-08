package com.github.analyticshub.dto;

import java.util.Map;

/**
 * 项目健康检查结果
 */
public record ProjectHealthResult(boolean connected, Map<String, Boolean> tables, boolean allTablesExist, String error) {}
