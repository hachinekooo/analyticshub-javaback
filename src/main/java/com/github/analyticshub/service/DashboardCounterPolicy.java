package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在 Dashboard 写入边界校验项目级 Counter 引用。
 *
 * <p>新加入的 Key 必须存在于当前项目；历史 Counter 后来被删除时允许原样保留，
 * 使无关布局编辑仍可保存，并由展示与配置界面明确提示失效引用。</p>
 */
@Component
public class DashboardCounterPolicy {

    private final CounterService counterService;

    public DashboardCounterPolicy(CounterService counterService) {
        this.counterService = counterService;
    }

    public void validateForWrite(String projectId, JsonNode definition, JsonNode existingDefinition) {
        Set<String> requestedKeys = collectCounterKeys(definition);
        requestedKeys.removeAll(collectCounterKeys(existingDefinition));
        if (requestedKeys.isEmpty()) return;

        Set<String> existingKeys = counterService.existingKeys(projectId, List.copyOf(requestedKeys));
        List<String> unavailableKeys = requestedKeys.stream()
                .filter(key -> !existingKeys.contains(key))
                .toList();
        if (!unavailableKeys.isEmpty()) {
            throw new BusinessException(
                    "DASHBOARD_COUNTER_UNAVAILABLE",
                    "Dashboard 引用了当前项目不存在的 Counter: " + String.join(", ", unavailableKeys)
            );
        }
    }

    private static Set<String> collectCounterKeys(JsonNode definition) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonNode widgets = definition == null ? null : definition.get("widgets");
        if (widgets == null || !widgets.isArray()) return keys;
        for (JsonNode widget : widgets) {
            if (!"core.counters".equals(widget.path("type").stringValue())) continue;
            JsonNode counterKeys = widget.path("config").path("keys");
            if (!counterKeys.isArray()) continue;
            for (JsonNode counterKey : counterKeys) {
                String value = counterKey.stringValue();
                if (value != null) keys.add(value);
            }
        }
        return keys;
    }
}
