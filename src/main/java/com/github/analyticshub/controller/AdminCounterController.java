package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.CounterIncrementRequest;
import com.github.analyticshub.dto.CounterRecord;
import com.github.analyticshub.dto.CounterUpsertRequest;
import com.github.analyticshub.dto.CountersResponse;
import com.github.analyticshub.service.CounterService;
import com.github.analyticshub.service.EventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理项目业务库中的长期累计计数器及其事件重建规则。
 */
@RestController
@RequestMapping("/api/admin/counters")
public class AdminCounterController {

    private final CounterService counterService;
    private final EventService eventService;

    public AdminCounterController(CounterService counterService, EventService eventService) {
        this.counterService = counterService;
        this.eventService = eventService;
    }

    /** 返回当前项目的全部计数器，包括非公开计数器。 */
    @GetMapping
    public ApiResponse<CountersResponse> list(@RequestParam("projectId") String projectId) {
        return ApiResponse.success(counterService.list(projectId, false));
    }

    /** 返回项目中已采集的事件类型，供计数器规则编辑器选择。 */
    @GetMapping("/metadata/event-types")
    public ApiResponse<java.util.List<String>> getEventTypes(@RequestParam("projectId") String projectId) {
        return ApiResponse.success(eventService.getDistinctEventTypes(projectId));
    }

    /** 返回指定计数器及其显示信息、触发规则和最近重建结果。 */
    @GetMapping("/{key}")
    public ApiResponse<CounterRecord> get(@RequestParam("projectId") String projectId,
                                          @PathVariable("key") String key) {
        return ApiResponse.success(counterService.get(projectId, key, false));
    }

    /** 创建或更新计数器元数据；不会自动重算历史事件。 */
    @PutMapping("/{key}")
    public ApiResponse<CounterRecord> upsert(@RequestParam("projectId") String projectId,
                                             @PathVariable("key") String key,
                                             @Valid @RequestBody(required = false) CounterUpsertRequest request) {
        return ApiResponse.success(counterService.upsert(projectId, key, request));
    }

    /** 删除计数器定义，不删除作为事实来源的历史事件。 */
    @DeleteMapping("/{key}")
    public ApiResponse<Void> delete(@RequestParam("projectId") String projectId,
                                    @PathVariable("key") String key) {
        counterService.delete(projectId, key);
        return ApiResponse.success(null);
    }

    /** 人工增加或扣减当前累计值；缺省 delta 为 1。 */
    @PostMapping("/{key}/increment")
    public ApiResponse<CounterRecord> increment(@RequestParam("projectId") String projectId,
                                                @PathVariable("key") String key,
                                                @Valid @RequestBody(required = false) CounterIncrementRequest request) {
        long delta = request == null || request.delta() == null ? 1L : request.delta();
        return ApiResponse.success(counterService.increment(projectId, key, delta));
    }

    /** 按已保存的事件触发规则重新扫描历史事件并覆盖累计值。 */
    @PostMapping("/{key}/rebuild")
    public ApiResponse<CounterRecord> rebuild(@RequestParam("projectId") String projectId,
                                              @PathVariable("key") String key) {
        return ApiResponse.success(counterService.rebuild(projectId, key));
    }
}
