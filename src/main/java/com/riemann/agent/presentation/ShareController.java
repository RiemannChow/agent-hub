package com.riemann.agent.presentation;

import com.riemann.agent.application.ShareAppService;
import com.riemann.agent.domain.SharedConversation;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ResponseStatusException;

/**
 * 分享相关接口：
 *   POST /api/agent/share            -> 为某对话生成分享快照，返回 shareId 与可访问链接
 *   GET  /api/agent/share/{shareId}  -> 返回分享快照 JSON（供只读页面渲染）
 */
@RestController
@RequestMapping("/api/agent")
public class ShareController {

    private final ShareAppService shareAppService;

    public ShareController(ShareAppService shareAppService) {
        this.shareAppService = shareAppService;
    }

    public record ShareRequest(String userId, String conversationId) {}

    public record ShareResponse(String shareId, String url) {}

    @PostMapping("/share")
    public Mono<ShareResponse> create(@RequestBody ShareRequest req, ServerHttpRequest request) {
        if (req.conversationId() == null || req.conversationId().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 不能为空"));
        }
        String userId = (req.userId() == null || req.userId().isBlank()) ? "web-user" : req.userId();
        String base = request.getURI().getScheme() + "://" + request.getURI().getAuthority();
        return shareAppService.createShare(userId, req.conversationId())
                .map(shareId -> new ShareResponse(shareId, base + "/share/" + shareId));
    }

    @GetMapping("/share/{shareId}")
    public Mono<SharedConversation> get(@PathVariable String shareId) {
        return shareAppService.getShare(shareId)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "分享不存在或已过期")));
    }
}
