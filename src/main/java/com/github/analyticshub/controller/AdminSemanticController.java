package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionsResponse;
import com.github.analyticshub.dto.AnalysisPackImportRequest;
import com.github.analyticshub.dto.AnalysisPackDetailResponse;
import com.github.analyticshub.dto.AnalysisPackResponse;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionRequest;
import com.github.analyticshub.dto.AnalyticsMetricDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsMetricResultResponse;
import com.github.analyticshub.dto.EventCatalogResponse;
import com.github.analyticshub.dto.SemanticDefinitionResponse;
import com.github.analyticshub.dto.SemanticDefinitionUpsertRequest;
import com.github.analyticshub.dto.SemanticDefinitionsResponse;
import com.github.analyticshub.dto.SemanticDeleteResponse;
import com.github.analyticshub.dto.TrustedSchemaPolicyResponse;
import com.github.analyticshub.service.SemanticDictionaryService;
import com.github.analyticshub.service.AnalyticsPropertyDefinitionService;
import com.github.analyticshub.service.AnalysisConfigurationService;
import com.github.analyticshub.service.AnalyticsMetricEvaluationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理项目级语义字典，并把项目业务库的原始事件目录与系统库语义定义合并展示。
 */
@RestController
@RequestMapping("/api/admin/projects/{projectId}")
public class AdminSemanticController {

    private final SemanticDictionaryService semanticDictionaryService;
    private final AnalyticsPropertyDefinitionService propertyDefinitionService;
    private final AnalysisConfigurationService analysisConfigurationService;
    private final AnalyticsMetricEvaluationService metricEvaluationService;

    public AdminSemanticController(
            SemanticDictionaryService semanticDictionaryService,
            AnalyticsPropertyDefinitionService propertyDefinitionService,
            AnalysisConfigurationService analysisConfigurationService,
            AnalyticsMetricEvaluationService metricEvaluationService
    ) {
        this.semanticDictionaryService = semanticDictionaryService;
        this.propertyDefinitionService = propertyDefinitionService;
        this.analysisConfigurationService = analysisConfigurationService;
        this.metricEvaluationService = metricEvaluationService;
    }

    /** 查询已采集原始事件及其当前语义解析，不执行跨数据库 JOIN。 */
    @GetMapping("/event-catalog")
    public ApiResponse<EventCatalogResponse> eventCatalog(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(semanticDictionaryService.getEventCatalog(projectId, sourceKind));
    }

    /** 返回项目系统库中维护的语义定义和原始 Key aliases。 */
    @GetMapping("/semantics")
    public ApiResponse<SemanticDefinitionsResponse> listDefinitions(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(semanticDictionaryService.listDefinitions(projectId, sourceKind));
    }

    /** 返回一个稳定 semantic key 的完整定义。 */
    @GetMapping("/semantics/{semanticKey}")
    public ApiResponse<SemanticDefinitionResponse> getDefinition(
            @PathVariable("projectId") String projectId,
            @PathVariable("semanticKey") String semanticKey,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(
                semanticDictionaryService.getDefinition(projectId, sourceKind, semanticKey)
        );
    }

    /** 创建或更新语义定义；REPLACE 模式会原子替换其 aliases。 */
    @PutMapping("/semantics/{semanticKey}")
    public ApiResponse<SemanticDefinitionResponse> upsertDefinition(
            @PathVariable("projectId") String projectId,
            @PathVariable("semanticKey") String semanticKey,
            @Valid @RequestBody SemanticDefinitionUpsertRequest request
    ) {
        return ApiResponse.success(
                semanticDictionaryService.upsertDefinition(projectId, semanticKey, request)
        );
    }

    /** 删除语义定义及其 aliases，不修改项目库中的原始事件事实。 */
    @DeleteMapping("/semantics/{semanticKey}")
    public ApiResponse<SemanticDeleteResponse> deleteDefinition(
            @PathVariable("projectId") String projectId,
            @PathVariable("semanticKey") String semanticKey,
            @RequestParam(value = "sourceKind", defaultValue = "EVENT_TYPE") String sourceKind
    ) {
        return ApiResponse.success(
                semanticDictionaryService.deleteDefinition(projectId, sourceKind, semanticKey)
        );
    }

    /** 返回可供筛选、分组和旅程关联使用的项目级属性语义。 */
    @GetMapping("/properties")
    public ApiResponse<AnalyticsPropertyDefinitionsResponse> listProperties(
            @PathVariable("projectId") String projectId
    ) {
        return ApiResponse.success(propertyDefinitionService.list(projectId));
    }

    /** 创建或更新一个顶层事件属性定义，不改写任何历史事件事实。 */
    @PutMapping("/properties/{propertyKey}")
    public ApiResponse<AnalyticsPropertyDefinitionResponse> upsertProperty(
            @PathVariable("projectId") String projectId,
            @PathVariable("propertyKey") String propertyKey,
            @Valid @RequestBody AnalyticsPropertyDefinitionRequest request
    ) {
        return ApiResponse.success(propertyDefinitionService.upsert(projectId, propertyKey, request));
    }

    @GetMapping("/metrics")
    public ApiResponse<java.util.List<AnalyticsMetricDefinitionResponse>> listMetrics(
            @PathVariable("projectId") String projectId
    ) {
        return ApiResponse.success(analysisConfigurationService.listMetrics(projectId));
    }

    /** 返回项目 Analysis Pack 当前声明的可信事件协议边界；未声明时 data 为 null。 */
    @GetMapping("/trusted-schema-policy")
    public ApiResponse<TrustedSchemaPolicyResponse> trustedSchemaPolicy(
            @PathVariable("projectId") String projectId
    ) {
        return ApiResponse.success(analysisConfigurationService.getTrustedSchemaPolicy(projectId));
    }

    @PutMapping("/metrics/{metricKey}")
    public ApiResponse<AnalyticsMetricDefinitionResponse> upsertMetric(
            @PathVariable("projectId") String projectId,
            @PathVariable("metricKey") String metricKey,
            @Valid @RequestBody AnalyticsMetricDefinitionRequest request
    ) {
        return ApiResponse.success(analysisConfigurationService.upsertMetric(projectId, metricKey, request));
    }

    @GetMapping("/metric-results/{metricKey}")
    public ApiResponse<AnalyticsMetricResultResponse> metricResult(
            @PathVariable("projectId") String projectId,
            @PathVariable("metricKey") String metricKey,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    ) {
        return ApiResponse.success(metricEvaluationService.evaluate(projectId, metricKey, from, to));
    }

    /** 原子导入声明式项目分析配置，并记录版本、校验和及审计事实。 */
    @GetMapping("/analysis-packs")
    public ApiResponse<java.util.List<AnalysisPackDetailResponse>> listAnalysisPacks(
            @PathVariable("projectId") String projectId
    ) {
        return ApiResponse.success(analysisConfigurationService.listAnalysisPacks(projectId));
    }

    /** 原子导入声明式项目分析配置，并记录版本、校验和及审计事实。 */
    @PutMapping("/analysis-packs/{packKey}")
    public ApiResponse<AnalysisPackResponse> importAnalysisPack(
            @PathVariable("projectId") String projectId,
            @PathVariable("packKey") String packKey,
            @Valid @RequestBody AnalysisPackImportRequest request
    ) {
        return ApiResponse.success(analysisConfigurationService.importPack(projectId, packKey, request));
    }
}
