package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsPropertyFilterServiceTest {

    private AnalyticsPropertyDefinitionService definitions;
    private AnalyticsPropertyFilterService service;

    @BeforeEach
    void setUp() {
        definitions = mock(AnalyticsPropertyDefinitionService.class);
        service = new AnalyticsPropertyFilterService(JsonMapper.builder().build(), definitions);
    }

    @Test
    void emptyInputKeepsLegacyQueryUnfiltered() {
        assertThat(service.compile("project", null, "properties").isEmpty()).isTrue();
        assertThat(service.compile("project", "[]", "properties").isEmpty()).isTrue();
    }

    @Test
    void compilesRegisteredStringEqualityWithBoundArguments() {
        AnalyticsPropertyDefinitionResponse definition = definition(
                "event_schema_version", AnalyticsPropertyDataType.STRING, true, false, false, false,
                List.of("3")
        );
        when(definitions.requireCapabilities("project", List.of("event_schema_version")))
                .thenReturn(Map.of("event_schema_version", definition));

        AnalyticsPropertyFilterService.CompiledPropertyFilters compiled = service.compile(
                "project",
                "[{\"propertyKey\":\"event_schema_version\",\"operator\":\"EQ\",\"values\":[\"3\"]}]",
                "properties"
        );

        assertThat(compiled.sql()).isEqualTo(
                "((jsonb_typeof(properties -> ?) = 'string' AND btrim(properties ->> ?, E' \\t\\n\\r\\f') = ?))"
        );
        assertThat(compiled.arguments()).containsExactly("event_schema_version", "event_schema_version", "3");
    }

    @Test
    void rejectsDuplicateKeysAndCapabilityEscalation() {
        AnalyticsPropertyDefinitionResponse nonFilterable = definition(
                "channel", AnalyticsPropertyDataType.STRING, false, true, false, false, null
        );
        when(definitions.requireCapabilities("project", List.of("channel")))
                .thenReturn(Map.of("channel", nonFilterable));

        assertThatThrownBy(() -> service.compile(
                "project",
                "[{\"propertyKey\":\"channel\",\"operator\":\"EXISTS\",\"values\":[]}]",
                "properties"
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用筛选能力");
    }

    @Test
    void validatesTypedValuesBeforeBuildingSql() {
        AnalyticsPropertyDefinitionResponse definition = definition(
                "attempt", AnalyticsPropertyDataType.INTEGER, true, false, false, false, null
        );
        when(definitions.requireCapabilities("project", List.of("attempt")))
                .thenReturn(Map.of("attempt", definition));

        assertThatThrownBy(() -> service.compile(
                "project",
                "[{\"propertyKey\":\"attempt\",\"operator\":\"EQ\",\"values\":[\"1.5\"]}]",
                "properties"
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("INTEGER");
    }

    @Test
    void rejectsNullFilterItemAsAStableClientError() {
        assertThatThrownBy(() -> service.compile("project", "[null]", "properties"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_ANALYTICS_PROPERTY_FILTER")
                )
                .hasMessageContaining("不完整");
    }

    private static AnalyticsPropertyDefinitionResponse definition(
            String key,
            AnalyticsPropertyDataType type,
            boolean filterable,
            boolean groupable,
            boolean journeyKey,
            boolean sensitive,
            List<String> allowedValues
    ) {
        return new AnalyticsPropertyDefinitionResponse(
                "project", key, Map.of("default", key), type, null, allowedValues,
                filterable, groupable, journeyKey, sensitive, true, Instant.EPOCH, Instant.EPOCH
        );
    }
}
