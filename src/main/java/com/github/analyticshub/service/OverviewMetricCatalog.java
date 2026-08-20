package com.github.analyticshub.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 核心概览可公开配置的稳定指标目录。
 *
 * <p>system.* 由分析事实直接计算；core.* 复用官方语义 Key，只有项目完成有效映射后才可展示。</p>
 */
final class OverviewMetricCatalog {

    static final String ACTIVE_DEVICES = "system.active_devices";
    static final String ACTIVE_ACTORS = "system.active_actors";
    static final String EVENT_OCCURRENCES = "system.event_occurrences";
    static final String TOP_ACTIVE_APP_VERSION = "system.top_active_app_version";
    static final String ACCOUNT_CREATED = "core.account.created";
    static final String ACCOUNT_RECREATED = "core.account.recreated";

    private static final List<String> SYSTEM_OVERVIEW_KEYS = List.of(
            ACTIVE_DEVICES,
            ACTIVE_ACTORS,
            EVENT_OCCURRENCES,
            TOP_ACTIVE_APP_VERSION
    );
    private static final List<String> SYSTEM_TREND_KEYS = List.of(ACTIVE_ACTORS, ACTIVE_DEVICES);
    private static final List<String> BUSINESS_KEYS = List.of(ACCOUNT_CREATED, ACCOUNT_RECREATED);
    private static final Set<String> SUPPORTED_KEYS;

    static {
        LinkedHashSet<String> keys = new LinkedHashSet<>(SYSTEM_OVERVIEW_KEYS);
        keys.addAll(BUSINESS_KEYS);
        SUPPORTED_KEYS = Set.copyOf(keys);
    }

    private OverviewMetricCatalog() {}

    static Set<String> supportedKeys() {
        return SUPPORTED_KEYS;
    }

    static boolean isBusinessKey(String key) {
        return BUSINESS_KEYS.contains(key);
    }

    static List<String> availableOverviewKeys(Map<String, List<String>> businessAliases) {
        List<String> available = new ArrayList<>(SYSTEM_OVERVIEW_KEYS);
        appendMappedBusinessKeys(available, businessAliases);
        return List.copyOf(available);
    }

    static List<String> availableTrendKeys(Map<String, List<String>> businessAliases) {
        List<String> available = new ArrayList<>(SYSTEM_TREND_KEYS);
        appendMappedBusinessKeys(available, businessAliases);
        return List.copyOf(available);
    }

    private static void appendMappedBusinessKeys(
            List<String> destination,
            Map<String, List<String>> businessAliases
    ) {
        for (String key : BUSINESS_KEYS) {
            if (!businessAliases.getOrDefault(key, List.of()).isEmpty()) {
                destination.add(key);
            }
        }
    }
}
