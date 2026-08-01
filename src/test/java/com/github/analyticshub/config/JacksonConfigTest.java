package com.github.analyticshub.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonConfigTest {

    private final JsonMapper objectMapper = new JacksonConfig(3, 8, 4)
            .createBuilder(Stream.empty())
            .build();

    @Test
    void appliesConfiguredNestingLimit() throws Exception {
        assertThat(objectMapper.readTree("{\"a\":{\"b\":1}}"))
                .isNotNull();

        assertThatThrownBy(() -> objectMapper.readTree("{\"a\":{\"b\":{\"c\":{\"d\":1}}}}"))
                .hasMessageContaining("nesting depth");
    }

    @Test
    void appliesConfiguredStringLengthLimit() {
        assertThatThrownBy(() -> objectMapper.readTree("{\"value\":\"123456789\"}"))
                .hasMessageContaining("String value length");
    }

    @Test
    void appliesConfiguredNumberLengthLimit() {
        assertThatThrownBy(() -> objectMapper.readTree("{\"value\":12345}"))
                .hasMessageContaining("Number value length");
    }

    @Test
    void rejectsNonPositiveLimitsAtStartup() {
        assertThatThrownBy(() -> new JacksonConfig(0, 8, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNestingDepth");
    }
}
