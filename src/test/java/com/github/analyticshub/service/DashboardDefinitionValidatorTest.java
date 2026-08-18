package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.extension.dashboard.DashboardWidgetExtension;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardDefinitionValidatorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final DashboardDefinitionValidator validator = new DashboardDefinitionValidator();

    @Test
    void acceptsTypedCoreWidgets() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "defaultRange": "7d",
                  "widgets": [
                    {
                      "id": "overview-main",
                      "type": "core.overview",
                      "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                      "config": {"title": "Overview"}
                    },
                    {
                      "id": "top-events",
                      "type": "core.topEvents",
                      "layout": {"x": 0, "y": 4, "w": 6, "h": 8},
                      "config": {"aggregation": "semantic", "limit": 10}
                    },
                    {
                      "id": "traffic-overview",
                      "type": "core.trafficOverview",
                      "layout": {"x": 6, "y": 4, "w": 6, "h": 4}
                    },
                    {
                      "id": "top-referrers",
                      "type": "core.topReferrers",
                      "layout": {"x": 6, "y": 8, "w": 6, "h": 4},
                      "config": {"limit": 8}
                    }
                  ]
                }
                """);

        assertThatCode(() -> validator.validate(1, definition)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutableOrRemoteFields() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "unsafe",
                    "type": "core.overview",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                    "config": {"html": "<script>alert(1)</script>", "url": "https://example.invalid"}
                  }]
                }
                """);

        assertInvalid(definition, "不支持的字段");
    }

    @Test
    void rejectsUnknownWidgetTypes() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "remote",
                    "type": "remote.javascript",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                    "config": {}
                  }]
                }
                """);

        assertInvalid(definition, "不支持的 widget type");
    }

    @Test
    void rejectsDuplicateWidgetTypesBecauseRuntimeDataIsInstanceScopedInFutureSchema() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [
                    {"id":"overview-a","type":"core.overview","layout":{"x":0,"y":0,"w":6,"h":4}},
                    {"id":"overview-b","type":"core.overview","layout":{"x":6,"y":0,"w":6,"h":4}}
                  ]
                }
                """);

        assertInvalid(definition, "同一 widget type 只能出现一次");
    }

    @Test
    void rejectsDuplicateWidgetIdsAndInvalidGridBounds() throws Exception {
        var duplicate = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [
                    {"id":"same","type":"core.overview","layout":{"x":0,"y":0,"w":12,"h":4}},
                    {"id":"same","type":"core.overview","layout":{"x":0,"y":4,"w":12,"h":4}}
                  ]
                }
                """);
        assertInvalid(duplicate, "不能重复");

        var overflow = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [
                    {"id":"wide","type":"core.overview","layout":{"x":8,"y":0,"w":8,"h":4}}
                  ]
                }
                """);
        assertInvalid(overflow, "12 列");
    }

    @Test
    void displayNameTreatsMarkupAsPlainTextConfiguration() throws Exception {
        var displayName = objectMapper.readTree("""
                {"zh-CN":"<script>alert(1)</script>","en":"Dashboard"}
                """);

        assertThatCode(() -> validator.validateDisplayName(displayName))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresConfigurationForWidgetsThatCannotRunWithoutIt() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "funnel",
                    "type": "core.productFunnel",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4}
                  }]
                }
                """);

        assertInvalid(definition, "必填 object");
    }

    @Test
    void acceptsFunnelJourneyCorrelationProperty() throws Exception {
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "paywall-funnel",
                    "type": "core.productFunnel",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                    "config": {
                      "steps": ["paywall_viewed", "purchase_succeeded"],
                      "groupBy": "entry_point",
                      "journeyKey": "paywall_flow_id"
                    }
                  }]
                }
                """);

        assertThatCode(() -> validator.validate(1, definition))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateConfigValuesAndImpossibleMinimumLayout() throws Exception {
        var duplicateSteps = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "funnel",
                    "type": "core.productFunnel",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                    "config": {"steps": ["started", "started"]}
                  }]
                }
                """);
        assertInvalid(duplicateSteps, "不能包含重复值");

        var impossibleMinimum = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "overview",
                    "type": "core.overview",
                    "layout": {"x": 0, "y": 0, "w": 4, "h": 4, "minW": 6}
                  }]
                }
                """);
        assertInvalid(impossibleMinimum, "minW 不能大于 w");
    }

    @Test
    void rejectsNonCanonicalTextAndAmbiguousLocales() throws Exception {
        var paddedId = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": " padded ",
                    "type": "core.overview",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4}
                  }]
                }
                """);
        assertInvalid(paddedId, "首尾空白");

        var locales = objectMapper.readTree("{\"en\":\"Dashboard\",\"EN\":\"Other\"}");
        assertThatThrownBy(() -> validator.validateDisplayName(locales))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getMessage()).contains("忽略大小写"));
    }

    @Test
    void matchesTheCurrentAnalyticsQueryLimits() throws Exception {
        var unsupportedGranularity = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "trends",
                    "type": "core.trends",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                    "config": {"granularity": "month"}
                  }]
                }
                """);
        assertInvalid(unsupportedGranularity, "granularity 格式无效");

        var unsupportedRetentionDay = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "retention",
                    "type": "core.retention",
                    "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
                    "config": {
                      "cohortEvent": "account.created",
                      "returnEvent": "app.opened",
                      "days": [1, 91]
                    }
                  }]
                }
                """);
        assertInvalid(unsupportedRetentionDay, "0 到 90");
    }

    @Test
    void acceptsOnlyRegisteredBuildTimeExtensionsAndTheirAllowListedConfig() throws Exception {
        DashboardDefinitionValidator extensionValidator = new DashboardDefinitionValidator(
                List.of(new ScoreWidgetExtension())
        );
        var definition = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "score",
                    "type": "custom.example.score",
                    "layout": {"x": 0, "y": 0, "w": 6, "h": 4},
                    "config": {"title": "Score", "threshold": 80}
                  }]
                }
                """);

        assertThatCode(() -> extensionValidator.validate(1, definition)).doesNotThrowAnyException();

        ((tools.jackson.databind.node.ObjectNode) definition
                .path("widgets").get(0).path("config")).put("html", "<script />");
        assertThatThrownBy(() -> extensionValidator.validate(1, definition))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getMessage()).contains("不支持的字段"));
    }

    @Test
    void rejectsInvalidOrDuplicateExtensionRegistrationsAtStartup() {
        DashboardWidgetExtension extension = new ScoreWidgetExtension();

        assertThatThrownBy(() -> new DashboardDefinitionValidator(List.of(extension, extension)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");
        assertThatThrownBy(() -> new DashboardDefinitionValidator(List.of(
                new DashboardWidgetExtension() {
                    @Override
                    public String type() {
                        return "core.overview";
                    }

                    @Override
                    public Set<String> allowedConfigFields() {
                        return Set.of();
                    }

                    @Override
                    public void validateConfig(JsonNode config) {
                    }
                }
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom.*");
    }

    @Test
    void snapshotsExtensionAllowListsAtStartupAndRejectsUnknownCustomTypes() throws Exception {
        Set<String> mutableFields = new HashSet<>(Set.of("threshold"));
        DashboardWidgetExtension extension = new DashboardWidgetExtension() {
            @Override
            public String type() {
                return "custom.example.mutable";
            }

            @Override
            public Set<String> allowedConfigFields() {
                return mutableFields;
            }

            @Override
            public void validateConfig(JsonNode config) {
            }
        };
        DashboardDefinitionValidator extensionValidator = new DashboardDefinitionValidator(List.of(extension));
        mutableFields.add("html");

        var changedAllowList = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "mutable",
                    "type": "custom.example.mutable",
                    "layout": {"x": 0, "y": 0, "w": 6, "h": 4},
                    "config": {"html": "<script />"}
                  }]
                }
                """);
        var unknownCustomType = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "widgets": [{
                    "id": "unknown",
                    "type": "custom.example.unknown",
                    "layout": {"x": 0, "y": 0, "w": 6, "h": 4}
                  }]
                }
                """);

        assertThatThrownBy(() -> extensionValidator.validate(1, changedAllowList))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getMessage()).contains("不支持的字段"));
        assertThatThrownBy(() -> extensionValidator.validate(1, unknownCustomType))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getMessage()).contains("不支持的 widget type"));
    }

    private void assertInvalid(tools.jackson.databind.JsonNode definition, String message) {
        assertThatThrownBy(() -> validator.validate(1, definition))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("INVALID_DASHBOARD_DEFINITION");
                    assertThat(exception.getMessage()).contains(message);
                });
    }

    private static final class ScoreWidgetExtension implements DashboardWidgetExtension {
        @Override
        public String type() {
            return "custom.example.score";
        }

        @Override
        public Set<String> allowedConfigFields() {
            return Set.of("threshold");
        }

        @Override
        public boolean configRequired() {
            return true;
        }

        @Override
        public void validateConfig(JsonNode config) {
            JsonNode threshold = config.get("threshold");
            if (threshold == null || !threshold.isInt()
                    || threshold.intValue() < 0 || threshold.intValue() > 100) {
                throw new IllegalArgumentException("threshold 必须是 0 到 100 的整数");
            }
        }
    }
}
