package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 Dashboard 写入边界校验项目级概览指标能力。
 *
 * <p>通用 system 指标不依赖业务配置；新加入的官方 core 指标只有在当前项目存在启用且非空的
 * 原始事件映射时才允许写入。已保存指标后来失效时可随无关编辑原样保留，展示层按实时可用性降级，
 * 避免静默删除配置或阻塞整个 Dashboard 的维护。</p>
 */
@Component
public class DashboardOverviewMetricPolicy {

    private final SemanticDictionaryService semanticDictionaryService;

    public DashboardOverviewMetricPolicy(SemanticDictionaryService semanticDictionaryService) {
        this.semanticDictionaryService = semanticDictionaryService;
    }

    public void validateForWrite(
            String projectId,
            JsonNode definition,
            JsonNode existingDefinition
    ) {
        Set<String> requestedBusinessKeys = collectRequestedBusinessKeys(definition);
        requestedBusinessKeys.removeAll(collectRequestedBusinessKeys(existingDefinition));
        if (requestedBusinessKeys.isEmpty()) return;

        Map<String, List<String>> aliases = semanticDictionaryService.resolveAvailableActiveEventAliases(
                projectId,
                List.copyOf(requestedBusinessKeys)
        );
        List<String> unavailableKeys = requestedBusinessKeys.stream()
                .filter(key -> aliases.getOrDefault(key, List.of()).isEmpty())
                .toList();
        if (!unavailableKeys.isEmpty()) {
            throw new BusinessException(
                    "DASHBOARD_METRIC_UNAVAILABLE",
                    "概览业务指标尚未配置有效的原始事件映射: " + String.join(", ", unavailableKeys)
            );
        }
    }

    private static Set<String> collectRequestedBusinessKeys(JsonNode definition) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonNode widgets = definition == null ? null : definition.get("widgets");
        if (widgets == null || !widgets.isArray()) return keys;
        for (JsonNode widget : widgets) {
            if (!"core.overview".equals(widget.path("type").stringValue())) continue;
            JsonNode metricKeys = widget.path("config").path("metricKeys");
            if (!metricKeys.isArray()) continue;
            for (JsonNode metricKey : metricKeys) {
                String value = metricKey.stringValue();
                if (OverviewMetricCatalog.isBusinessKey(value)) keys.add(value);
            }
        }
        return keys;
    }
}
