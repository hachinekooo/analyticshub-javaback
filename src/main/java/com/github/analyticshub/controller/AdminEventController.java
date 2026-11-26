package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.AdminEventsResponse;
import com.github.analyticshub.dto.AdminEventJourneyResponse;
import com.github.analyticshub.dto.AdminEventPropertiesResponse;
import com.github.analyticshub.service.AdminEventJourneyService;
import com.github.analyticshub.service.AdminEventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端事件查询接口
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/events")
public class AdminEventController {

    private final AdminEventQueryService adminEventQueryService;
    private final AdminEventJourneyService adminEventJourneyService;

    /**
     * 查询项目事件明细；身份、设备、时间和版本条件同时生效，返回的总数是事件条数，不是用户数。
     * 原始事件名与业务事件定义不可同时传；定义未登记或停用时返回 409，已启用但未配置事件名时返回零条。
     * 事件版本只筛选原始记录，不转换历史数据；例如传 {@code eventSchemaVersion=4} 只查 V4，不传则不限版本。
     * 不传时间默认查最近七天；只传结束时间则往前取七天。纯日期按 UTC 解释，结束日期包含当天，具体结束时刻不包含。
     * 排查单次操作可先从此列表取事件 ID，再调用 {@link #journey}；完整的参数和请求示例见 docs/API_MANAGEMENT.md。
     *
     * @param from 事件发生时间的起点，含边界；省略则从结束边界往前取七天。
     * @param to 结束日期（UTC，含当天）或结束时刻（不含该时刻）；省略则取当前时刻。
     * @param eventType 精确匹配原始事件名；不可与业务事件定义同时指定。
     * @param semanticEvent 项目的业务事件定义键；按已配置的原始事件名匹配。
     * @param userId 事件写入时的原始统计身份，匿名事件也可能有值。
     * @param resolvedActorId 原始或归一统计身份 UUID；会展开关联成员，未关联时只匹配自身。与 userId 同传时取交集。
     * @param eventSchemaVersion 正整数，例如 {@code 4}；不传则不区分事件版本，不是 App 版本。
     */
    @GetMapping
    public ApiResponse<AdminEventsResponse> list(
            @RequestParam("projectId") String projectId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "semanticEvent", required = false) String semanticEvent,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "resolvedActorId", required = false) String resolvedActorId,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "eventSchemaVersion", required = false) String eventSchemaVersion) {
        return ApiResponse.success(
                adminEventQueryService.listEvents(
                        projectId, from, to, page, pageSize, eventType, userId, resolvedActorId, deviceId,
                        parseEventSchemaVersion(eventSchemaVersion), semanticEvent
                )
        );
    }

    /**
     * 默认查锚点前后各 60 分钟；按归一身份匹配，锚点无有效身份 UUID 时按设备查，不沿用明细筛选。
     *
     * @param anchorEventId 从当前项目的事件明细取得；以该条事件的发生时间作为中心。
     * @param beforeMinutes 锚点之前的分钟数，默认 60，允许 1 到 10080。
     * @param afterMinutes 锚点之后的分钟数，默认 60，允许 1 到 10080，包含结束时刻的事件。
     */
    @GetMapping("/journey")
    public ApiResponse<AdminEventJourneyResponse> journey(
            @RequestParam("projectId") String projectId,
            @RequestParam("anchorEventId") String anchorEventId,
            @RequestParam(value = "beforeMinutes", required = false) Integer beforeMinutes,
            @RequestParam(value = "afterMinutes", required = false) Integer afterMinutes) {
        return ApiResponse.success(
                adminEventJourneyService.getJourney(
                        projectId, anchorEventId, beforeMinutes, afterMinutes
                )
        );
    }

    /** 按事件补查旅程未内嵌的属性；若已记录的属性大小超过 2 MiB，拒绝在线读取。 */
    @GetMapping("/properties")
    public ApiResponse<AdminEventPropertiesResponse> properties(
            @RequestParam("projectId") String projectId,
            @RequestParam("eventId") String eventId) {
        return ApiResponse.success(adminEventJourneyService.getEventProperties(projectId, eventId));
    }

    // ==================== 请求参数解析 ====================

    private static Integer parseEventSchemaVersion(String value) {
        if (value == null) {
            return null;
        }
        try {
            int version = Integer.parseInt(value);
            if (version > 0) {
                return version;
            }
        } catch (NumberFormatException ignored) {
            // 非数字和非正数走同一 400 / INVALID_ARGUMENT 响应。
        }
        throw new IllegalArgumentException("eventSchemaVersion 必须为正整数");
    }
}
