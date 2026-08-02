package com.github.analyticshub.service;

import com.github.analyticshub.dto.ProjectAnalysisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Initializes platform-owned semantic contracts for a project's selected template. */
@Service
public class SemanticTemplateService {

    private static final List<Preset> PRODUCT_PRESETS = List.of(
            new Preset("core.activation.completed", Map.of(
                    "zh-CN", "激活完成", "en", "Activation completed"
            ), "lifecycle", "用户完成产品定义的首次关键激活动作"),
            new Preset("core.action.completed", Map.of(
                    "zh-CN", "核心动作完成", "en", "Core action completed"
            ), "engagement", "用户完成产品最核心的业务动作"),
            new Preset("core.paywall.opened", Map.of(
                    "zh-CN", "付费墙打开", "en", "Paywall opened"
            ), "monetization", "用户打开付费页面或付费墙"),
            new Preset("core.purchase.completed", Map.of(
                    "zh-CN", "购买完成", "en", "Purchase completed"
            ), "monetization", "用户完成一次有效购买")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SemanticTemplateService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Idempotently inserts official contracts; aliases intentionally start empty. */
    public void initialize(String projectId, ProjectAnalysisTemplate template) {
        if (template != ProjectAnalysisTemplate.APP && template != ProjectAnalysisTemplate.WEB_APP) {
            return;
        }
        for (Preset preset : PRODUCT_PRESETS) {
            jdbcTemplate.update(
                    """
                    INSERT INTO analytics_semantic_definitions
                        (project_id, source_kind, semantic_key, definition_origin,
                         display_name, category, description, is_active)
                    VALUES (?, 'EVENT_TYPE', ?, 'OFFICIAL', ?::jsonb, ?, ?, TRUE)
                    ON CONFLICT (project_id, source_kind, semantic_key) DO NOTHING
                    """,
                    projectId,
                    preset.key(),
                    serializeDisplayName(preset.displayName()),
                    preset.category(),
                    preset.description()
            );
        }
    }

    private String serializeDisplayName(Map<String, String> names) {
        try {
            return objectMapper.writeValueAsString(names);
        } catch (JacksonException exception) {
            throw new IllegalStateException("官方语义模板序列化失败", exception);
        }
    }

    private record Preset(String key, Map<String, String> displayName, String category, String description) {}
}
