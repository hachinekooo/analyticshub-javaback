package com.github.analyticshub.config;

import com.github.analyticshub.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JacksonMvcIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class
            ))
            .withUserConfiguration(JacksonConfig.class)
            .withPropertyValues(
                    "app.json.max-nesting-depth=4",
                    "app.json.max-string-length=8",
                    "app.json.max-number-length=4",
                    "spring.jackson.default-property-inclusion=non_null",
                    "spring.jackson.time-zone=UTC"
            );

    @Test
    void mvcConverterUsesTheOnlyApplicationMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JsonMapper.class);
            assertThat(context).hasSingleBean(ObjectMapper.class);

            JsonMapper applicationMapper = context.getBean(JsonMapper.class);
            JacksonJsonHttpMessageConverter converter = jacksonConverter(messageConverters(context));

            assertThat(converter.getMapper()).isSameAs(applicationMapper);
            assertThat(context.getBeansOfType(Object.class).values())
                    .extracting(bean -> bean.getClass().getName())
                    .noneMatch(name -> name.startsWith("com.fasterxml.jackson.databind."));
        });
    }

    @Test
    void mvcRequestParsingUsesConfiguredStreamConstraints() {
        contextRunner.run(context -> {
            MockMvc mockMvc = mockMvc(jacksonConverter(messageConverters(context)));

            assertBadRequest(mockMvc, "{\"value\":\"123456789\"}");
            assertBadRequest(mockMvc, "{\"value\":12345}");
            assertBadRequest(mockMvc, "{\"value\":{\"a\":{\"b\":{\"c\":{\"d\":1}}}}}");
        });
    }

    @Test
    void mvcSerializesJavaTimeAsIsoTextAndHonorsBootInclusionSettings() {
        contextRunner.run(context -> {
            MockMvc mockMvc = mockMvc(jacksonConverter(messageConverters(context)));

            mockMvc.perform(get("/jackson-test/time"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.instant").value("2026-08-01T10:15:30Z"))
                    .andExpect(jsonPath("$.optional").doesNotExist());
        });
    }

    private static JacksonJsonHttpMessageConverter jacksonConverter(
            HttpMessageConverters converters
    ) {
        return java.util.stream.StreamSupport.stream(converters.spliterator(), false)
                .filter(JacksonJsonHttpMessageConverter.class::isInstance)
                .map(JacksonJsonHttpMessageConverter.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static HttpMessageConverters messageConverters(ApplicationContext context) {
        HttpMessageConverters.ServerBuilder builder = HttpMessageConverters.forServer();
        context.getBeanProvider(ServerHttpMessageConvertersCustomizer.class)
                .orderedStream()
                .forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    private static MockMvc mockMvc(JacksonJsonHttpMessageConverter converter) {
        return MockMvcBuilders.standaloneSetup(new JacksonProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private static void assertBadRequest(MockMvc mockMvc, String body) throws Exception {
        mockMvc.perform(post("/jackson-test/echo")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @RestController
    static class JacksonProbeController {

        @PostMapping("/jackson-test/echo")
        Map<String, JsonNode> echo(@RequestBody JsonProbe request) {
            return Map.of("value", request.value());
        }

        @GetMapping("/jackson-test/time")
        JavaTimeProbe time() {
            return new JavaTimeProbe(Instant.parse("2026-08-01T10:15:30Z"), null);
        }
    }

    record JsonProbe(JsonNode value) {}

    record JavaTimeProbe(Instant instant, String optional) {}
}
