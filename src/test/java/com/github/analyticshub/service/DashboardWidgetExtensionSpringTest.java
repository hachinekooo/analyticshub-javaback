package com.github.analyticshub.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.extension.dashboard.DashboardWidgetExtension;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardWidgetExtensionSpringTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void springInjectsBuildTimeExtensionBeansIntoTheValidator() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ScoreExtension.class);
            context.registerBean(DashboardDefinitionValidator.class);
            context.refresh();

            DashboardDefinitionValidator validator = context.getBean(DashboardDefinitionValidator.class);
            JsonNode definition = objectMapper.readTree("""
                    {
                      "schemaVersion": 1,
                      "widgets": [{
                        "id": "score",
                        "type": "custom.example.score",
                        "layout": {"x": 0, "y": 0, "w": 6, "h": 4},
                        "config": {"threshold": 80}
                      }]
                    }
                    """);

            assertThatCode(() -> validator.validate(1, definition)).doesNotThrowAnyException();
        }
    }

    @Test
    void invalidExtensionRegistrationFailsDuringSpringContextRefresh() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(CoreOverrideExtension.class);
        context.registerBean(DashboardDefinitionValidator.class);
        try {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining("custom.*");
        } finally {
            context.close();
        }
    }

    static final class ScoreExtension implements DashboardWidgetExtension {
        @Override
        public String type() {
            return "custom.example.score";
        }

        @Override
        public Set<String> allowedConfigFields() {
            return Set.of("threshold");
        }

        @Override
        public void validateConfig(JsonNode config) {
            if (!config.path("threshold").canConvertToInt()) {
                throw new IllegalArgumentException("threshold 必须是整数");
            }
        }
    }

    static final class CoreOverrideExtension implements DashboardWidgetExtension {
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
}
