package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsDataQualityIssue;
import com.github.analyticshub.dto.AnalyticsPropertyQuality;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsDataQualityServiceTest {

    @Test
    void propertyCoverageBudgetMatchesAcceptedEventPropertyBudget() {
        assertThat(AnalyticsDataQualityService.MAX_PROPERTY_COVERAGE_ITEMS)
                .isEqualTo(EventService.MAX_PROPERTIES_KEYS);
    }

    @Test
    void propertyTypeMismatchPreventsACleanQualityResult() {
        List<AnalyticsDataQualityIssue> issues = new ArrayList<>();

        AnalyticsDataQualityService.addPropertyCoverageIssues(
                issues,
                List.of(new AnalyticsPropertyQuality("release_channel", 10, 3, 0)),
                0
        );

        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("property_type_mismatch");
            assertThat(issue.severity()).isEqualTo("error");
            assertThat(issue.count()).isEqualTo(3);
        });
    }

    @Test
    void disallowedPropertyValuePreventsACleanQualityResult() {
        List<AnalyticsDataQualityIssue> issues = new ArrayList<>();

        AnalyticsDataQualityService.addPropertyCoverageIssues(
                issues,
                List.of(new AnalyticsPropertyQuality("event_schema_version", 10, 0, 4)),
                0
        );

        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("property_value_outside_allowlist");
            assertThat(issue.severity()).isEqualTo("error");
            assertThat(issue.count()).isEqualTo(4);
        });
    }

    @Test
    void truncatedPropertyCoverageIsReportedAsIncomplete() {
        List<AnalyticsDataQualityIssue> issues = new ArrayList<>();

        AnalyticsDataQualityService.addPropertyCoverageIssues(issues, List.of(), 2);

        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("property_coverage_truncated");
            assertThat(issue.severity()).isEqualTo("warning");
            assertThat(issue.count()).isEqualTo(2);
        });
    }
}
