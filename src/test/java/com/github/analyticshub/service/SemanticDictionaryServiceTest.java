package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.dto.SemanticAliasUpdateMode;
import com.github.analyticshub.dto.SemanticDefinitionUpsertRequest;
import com.github.analyticshub.dto.SemanticSourceKind;
import com.github.analyticshub.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SemanticDictionaryServiceTest {

    private final JdbcTemplate systemJdbcTemplate = mock(JdbcTemplate.class);
    private final MultiDataSourceManager dataSourceManager = mock(MultiDataSourceManager.class);
    private final AnalyticsSemanticDependencyService analyticsDependencies =
            mock(AnalyticsSemanticDependencyService.class);
    private final AnalysisPackOwnershipService packOwnershipService =
            mock(AnalysisPackOwnershipService.class);
    private final SemanticDictionaryService service = new SemanticDictionaryService(
            systemJdbcTemplate,
            dataSourceManager,
            JsonMapper.builder().build(),
            analyticsDependencies,
            packOwnershipService
    );

    @Test
    void sourceKindIsAnExplicitOneValueAllowListFor101() {
        assertThat(SemanticDictionaryService.parseSourceKind("EVENT_TYPE"))
                .isEqualTo(SemanticSourceKind.EVENT_TYPE);

        assertThatThrownBy(() -> SemanticDictionaryService.parseSourceKind("COUNTER_KEY"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("UNSUPPORTED_SEMANTIC_SOURCE_KIND");
                    assertThat(exception.getHttpStatus().value()).isEqualTo(400);
                });
    }

    @Test
    void preserveModeRejectsAliasesToAvoidAmbiguousMutation() {
        SemanticDefinitionUpsertRequest request = request(
                SemanticAliasUpdateMode.PRESERVE,
                List.of("item.completed")
        );

        assertThatThrownBy(() -> service.upsertDefinition("project_a", "content.completed", request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_SEMANTIC_DEFINITION"));
        verifyNoInteractions(systemJdbcTemplate, dataSourceManager);
    }

    @Test
    void replaceModeRequiresAliasesAndUsesEmptyArrayAsExplicitClear() {
        SemanticDefinitionUpsertRequest request = request(SemanticAliasUpdateMode.REPLACE, null);

        assertThatThrownBy(() -> service.upsertDefinition("project_a", "content.completed", request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getMessage()).contains("必须传 aliases"));
        verifyNoInteractions(systemJdbcTemplate, dataSourceManager);
    }

    @Test
    void canonicalSemanticKeyIsStrictAndRawAliasStillRejectsBlank() {
        assertThatThrownBy(() -> service.upsertDefinition(
                "project_a",
                "Content Completed",
                request(SemanticAliasUpdateMode.REPLACE, List.of())
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("INVALID_SEMANTIC_DEFINITION"));

        assertThatThrownBy(() -> service.upsertDefinition(
                "project_a",
                "content.completed",
                request(SemanticAliasUpdateMode.REPLACE, List.of("   "))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("INVALID_SEMANTIC_DEFINITION"));
        verifyNoInteractions(systemJdbcTemplate, dataSourceManager);
    }

    @Test
    void displayLocalesCannotBeAmbiguousByCase() {
        SemanticDefinitionUpsertRequest request = new SemanticDefinitionUpsertRequest(
                SemanticSourceKind.EVENT_TYPE,
                Map.of("en", "Content completed", "EN", "Other display"),
                null,
                null,
                true,
                SemanticAliasUpdateMode.REPLACE,
                List.of()
        );

        assertThatThrownBy(() -> service.upsertDefinition("project_a", "content.completed", request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getMessage()).contains("忽略大小写后不能重复"));
        verifyNoInteractions(systemJdbcTemplate, dataSourceManager);
    }

    private static SemanticDefinitionUpsertRequest request(
            SemanticAliasUpdateMode mode,
            List<String> aliases
    ) {
        return new SemanticDefinitionUpsertRequest(
                SemanticSourceKind.EVENT_TYPE,
                Map.of("default", "Content completed", "zh-CN", "内容已完成"),
                "engagement",
                "A generic completion event",
                true,
                mode,
                aliases
        );
    }
}
