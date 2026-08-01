package com.github.analyticshub.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Stream;

/**
 * Jackson 配置
 */
@Configuration
public class JacksonConfig {

    private final int maxNestingDepth;
    private final int maxStringLength;
    private final int maxNumberLength;

    public JacksonConfig(
            @Value("${app.json.max-nesting-depth:64}") int maxNestingDepth,
            @Value("${app.json.max-string-length:262144}") int maxStringLength,
            @Value("${app.json.max-number-length:128}") int maxNumberLength
    ) {
        this.maxNestingDepth = requirePositive(maxNestingDepth, "maxNestingDepth");
        this.maxStringLength = requirePositive(maxStringLength, "maxStringLength");
        this.maxNumberLength = requirePositive(maxNumberLength, "maxNumberLength");
    }

    /**
     * Replaces Boot's prototype builder only to install immutable stream
     * constraints on the underlying JsonFactory. Every Boot customizer is
     * still applied in ordered form before Boot builds its single primary
     * JsonMapper, which is also used by Spring MVC.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public JsonMapper.Builder jsonMapperBuilder(
            ObjectProvider<JsonMapperBuilderCustomizer> customizers
    ) {
        return createBuilder(customizers.orderedStream());
    }

    JsonMapper.Builder createBuilder(Stream<JsonMapperBuilderCustomizer> customizers) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(maxNestingDepth)
                .maxStringLength(maxStringLength)
                .maxNumberLength(maxNumberLength)
                .build();
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        JsonMapper.Builder builder = JsonMapper.builder(jsonFactory);
        customizers.forEach(customizer -> customizer.customize(builder));
        return builder;
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }
}
