package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsPropertyValueNormalizerTest {

    @Test
    void canonicalizesEveryTypedPropertyValue() {
        assertThat(AnalyticsPropertyValueNormalizer.normalize(" TRUE ", AnalyticsPropertyDataType.BOOLEAN))
                .isEqualTo("true");
        assertThat(AnalyticsPropertyValueNormalizer.normalize("03", AnalyticsPropertyDataType.INTEGER))
                .isEqualTo("3");
        assertThat(AnalyticsPropertyValueNormalizer.normalize("1.0", AnalyticsPropertyDataType.NUMBER))
                .isEqualTo("1");
        assertThat(AnalyticsPropertyValueNormalizer.normalize("\t stable \n", AnalyticsPropertyDataType.STRING))
                .isEqualTo("stable");
    }
}
