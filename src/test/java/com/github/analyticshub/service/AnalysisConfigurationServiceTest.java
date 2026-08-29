package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalysisPackImportRequest;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisConfigurationServiceTest {

    private ObjectMapper objectMapper;
    private AnalysisConfigurationService service;
    private AnalyticsPropertyFilterService propertyFilterService;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        AnalyticsPropertyDefinitionService properties = new AnalyticsPropertyDefinitionService(
                jdbcTemplate, objectMapper, mock(AnalysisPackOwnershipService.class)
        );
        propertyFilterService = mock(AnalyticsPropertyFilterService.class);
        service = new AnalysisConfigurationService(
                jdbcTemplate,
                objectMapper,
                properties,
                propertyFilterService,
                mock(SemanticDictionaryService.class),
                mock(AnalysisPackOwnershipService.class),
                mock(AnalyticsMetricDependencyService.class)
        );
    }

    @Test
    void packReportsNestedPropertyValidationAsStableClientError() throws Exception {
        AnalysisPackImportRequest request = new AnalysisPackImportRequest(
                1,
                Map.of("en", "Test pack"),
                objectMapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "properties": [{
                            "propertyKey": "channel",
                            "displayName": {"en": "Channel"},
                            "dataType": "STRING",
                            "allowedValues": [null],
                            "filterable": true,
                            "groupable": false,
                            "journeyKey": false,
                            "sensitive": false,
                            "active": true
                          }],
                          "metrics": []
                        }
                        """),
                false
        );

        assertThatThrownBy(() -> service.importPack("project", "test.pack", request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION");
                    assertThat(exception.getHttpStatus().is4xxClientError()).isTrue();
                })
                .hasMessageContaining("properties[channel]")
                .hasMessageContaining("allowedValues");
    }

    @Test
    void packRejectsDuplicateFunnelStepsBeforePersistingAnUnevaluableMetric() throws Exception {
        AnalysisPackImportRequest request = new AnalysisPackImportRequest(
                1,
                Map.of("en", "Invalid funnel pack"),
                objectMapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "properties": [],
                          "metrics": [{
                            "metricKey": "conversion.duplicate",
                            "displayName": {"en": "Duplicate conversion"},
                            "metricType": "FUNNEL_CONVERSION",
                            "definition": {"steps": ["content.completed", " content.completed "]},
                            "active": true
                          }]
                        }
                        """),
                false
        );

        assertThatThrownBy(() -> service.importPack("project", "invalid.funnel", request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION")
                )
                .hasMessageContaining("不能包含重复事件");
    }

    @Test
    void packRejectsCrossVersionMetricWithoutAnAuditableReasonEvenWithoutPolicy() throws Exception {
        AnalysisPackImportRequest request = new AnalysisPackImportRequest(
                1,
                Map.of("en", "Unaudited metric pack"),
                objectMapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "properties": [],
                          "metrics": [{
                            "metricKey": "opens.cross_version",
                            "displayName": {"en": "Cross-version opens"},
                            "metricType": "EVENT_COUNT",
                            "definition": {
                              "semanticEvent": "app.open",
                              "schemaScope": "CROSS_VERSION_VERIFIED"
                            },
                            "active": true
                          }]
                        }
                        """),
                false
        );

        assertThatThrownBy(() -> service.importPack("project", "invalid.scope", request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION")
                )
                .hasMessageContaining("schemaScopeReason");
    }

    @Test
    void propertyBreakdownRejectsNestedOrNonTextBusinessLabels() throws Exception {
        when(propertyFilterService.requireGroupable("project", "opening_action"))
                .thenReturn(AnalyticsPropertyDataType.STRING);
        AnalysisPackImportRequest request = new AnalysisPackImportRequest(
                1,
                Map.of("en", "Invalid label pack"),
                objectMapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "properties": [],
                          "metrics": [{
                            "metricKey": "opening.action_mix",
                            "displayName": {"en": "Opening action mix"},
                            "metricType": "PROPERTY_BREAKDOWN",
                            "definition": {
                              "semanticEvent": "letter.opening.completed",
                              "aggregation": "EVENT_COUNT",
                              "groupBy": "opening_action",
                              "missingValuePolicy": "EXCLUDE",
                              "valueLabels": {
                                "skip": {"zh-CN": {"nested": "不允许"}}
                              }
                            },
                            "active": true
                          }]
                        }
                        """),
                false
        );

        assertThatThrownBy(() -> service.importPack("project", "invalid.labels", request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_ANALYSIS_CONFIGURATION")
                )
                .hasMessageContaining("valueLabels")
                .hasMessageContaining("多语言名称");
    }
}
