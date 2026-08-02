package com.github.analyticshub.dto;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 项目初始化时选择的分析模板。
 *
 * <p>模板只描述项目工作台的初始信息架构，不限制项目后续维护语义、
 * Dashboard 或 Counter。</p>
 */
public enum ProjectAnalysisTemplate {
    APP("app"),
    WEBSITE("website"),
    WEB_APP("webapp"),
    BLANK("blank");

    @EnumValue
    private final String value;

    ProjectAnalysisTemplate(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ProjectAnalysisTemplate fromValue(String value) {
        return Arrays.stream(values())
                .filter(template -> template.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("analysisTemplate 无效"));
    }
}
