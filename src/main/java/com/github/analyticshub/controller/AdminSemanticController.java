package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.EventCatalogResponse;
import com.github.analyticshub.dto.SemanticDefinitionResponse;
import com.github.analyticshub.dto.SemanticDefinitionUpsertRequest;
import com.github.analyticshub.dto.SemanticDefinitionsResponse;
import com.github.analyticshub.dto.SemanticDeleteResponse;
import com.github.analyticshub.service.SemanticDictionaryService;
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

    public AdminSemanticController(SemanticDictionaryService semanticDictionaryService) {
        this.semanticDictionaryService = semanticDictionaryService;
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
}
