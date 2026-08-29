package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionRequest;
import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsPropertyDefinitionServiceTest {

    private AnalyticsPropertyDefinitionService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        service = new AnalyticsPropertyDefinitionService(
                jdbcTemplate,
                JsonMapper.builder().build(),
                mock(AnalysisPackOwnershipService.class)
        );
    }

    @Test
    void rejectsNullAndBlankAllowedValuesBeforePersistence() {
        AnalyticsPropertyDefinitionRequest withNull = request(java.util.Arrays.asList("valid", null));
        AnalyticsPropertyDefinitionRequest withBlank = request(List.of("valid", "  "));

        assertThatThrownBy(() -> service.upsert("project", "channel", withNull))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("allowedValues");
        assertThatThrownBy(() -> service.upsert("project", "channel", withBlank))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("allowedValues");
    }

    @Test
    void rejectsInvalidLocalizedDisplayNameOutsideControllerValidation() {
        AnalyticsPropertyDefinitionRequest request = new AnalyticsPropertyDefinitionRequest(
                Map.of("invalid locale", "Channel"), AnalyticsPropertyDataType.STRING, null,
                null, true, false, false, false, true
        );

        assertThatThrownBy(() -> service.upsert("project", "channel", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void rejectsNonStringJourneyKeyBecauseJourneyExtractionUsesStringValues() {
        AnalyticsPropertyDefinitionRequest request = new AnalyticsPropertyDefinitionRequest(
                Map.of("en", "Journey"), AnalyticsPropertyDataType.INTEGER, null,
                null, false, false, true, false, true
        );

        assertThatThrownBy(() -> service.upsert("project", "journey_id", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("journeyKey")
                .hasMessageContaining("STRING");
    }

    private static AnalyticsPropertyDefinitionRequest request(List<String> allowedValues) {
        return new AnalyticsPropertyDefinitionRequest(
                Map.of("en", "Channel"), AnalyticsPropertyDataType.STRING, null,
                allowedValues, true, false, false, false, true
        );
    }
}
