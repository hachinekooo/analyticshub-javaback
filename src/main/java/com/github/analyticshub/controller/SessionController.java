package com.github.analyticshub.controller;

import com.github.analyticshub.common.dto.ApiResponse;
import com.github.analyticshub.dto.SessionUploadRequest;
import com.github.analyticshub.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 会话管理控制器
 * 处理会话上传相关请求
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private static final System.Logger log = System.getLogger(SessionController.class.getName());

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 会话上传
     * POST /api/v1/sessions
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> uploadSession(
            @Valid @RequestBody SessionUploadRequest request) {
        
        log.log(System.Logger.Level.DEBUG, "会话上传请求: sessionId={0}", request.sessionId());
        
        sessionService.uploadSession(request);
        return ApiResponse.success(null);
    }
}
