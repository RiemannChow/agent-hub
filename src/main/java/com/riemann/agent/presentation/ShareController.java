package com.riemann.agent.presentation;

import com.riemann.agent.application.ShareAppService;
import com.riemann.agent.auth.AuthenticatedUser;
import com.riemann.agent.domain.SharedConversation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 分享相关接口： POST /api/agent/share -> 为某对话生成分享快照，返回 shareId 与可访问链接 GET /api/agent/share/{shareId} ->
 * 返回分享快照 JSON（供只读页面渲染）
 */
@RestController
@RequestMapping("/api/agent")
public class ShareController {

    private final ShareAppService shareAppService;

    public ShareController(ShareAppService shareAppService) {
        this.shareAppService = shareAppService;
    }

    /**
     * {@code userId} is retained for UI compatibility but ignored; see {@link AuthenticatedUser}.
     */
    public record ShareRequest(String userId, String conversationId) {}

    public record ShareResponse(String shareId, String url) {}

    @PostMapping("/share")
    public Mono<ShareResponse> create(@RequestBody ShareRequest req, ServerWebExchange exchange) {
        if (req.conversationId() == null || req.conversationId().isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 不能为空"));
        }
        // The identity comes from the session, not from the request body: otherwise anyone could
        // create a public snapshot of another user's conversation.
        String userId = AuthenticatedUser.require(exchange);
        String base =
                exchange.getRequest().getURI().getScheme()
                        + "://"
                        + exchange.getRequest().getURI().getAuthority();
        return shareAppService
                .createShare(userId, req.conversationId())
                .map(shareId -> new ShareResponse(shareId, base + "/share/" + shareId));
    }

    @GetMapping("/share/{shareId}")
    public Mono<SharedConversation> get(@PathVariable String shareId) {
        return shareAppService
                .getShare(shareId)
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "分享不存在或已过期")));
    }
}
