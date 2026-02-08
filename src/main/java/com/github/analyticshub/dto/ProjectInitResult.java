package com.github.analyticshub.dto;

import java.util.List;

/**
 * 项目数据库初始化结果
 */
public record ProjectInitResult(String message, List<String> tables) {}
