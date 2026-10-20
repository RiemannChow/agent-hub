package com.riemann.agent.presentation;

import com.riemann.agent.application.ConversationAppService;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.UUID;

/**
 * SSE 控制器。
 *
 *  POST /api/agent/chat                              -> 接收用户输入，返回 text/event-stream
 *  GET  /api/agent/chat/{conversationId}/stream      -> 订阅已有会话的事件流
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private final ConversationAppService appService;
    private final ConversationEventBus eventBus;

    public AgentChatController(ConversationAppService appService, ConversationEventBus eventBus) {
        this.appService = appService;
        this.eventBus = eventBus;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> chat(@RequestBody ChatRequest request) {
        ConversationId id = (request.conversationId() != null && !request.conversationId().isBlank())
                ? new ConversationId(request.conversationId())
                : new ConversationId(UUID.randomUUID().toString());

        // POST 与 GET 订阅者共享同一个多播 Sink
        Sinks.Many<AgentEvent> sink = eventBus.register(id);
        Flux<AgentEvent> events = appService.chat(request.userId(), request.message(), id);
        events.subscribe(sink::tryEmitNext, sink::tryEmitError, sink::tryEmitComplete);

        return sink.asFlux()
                .map(this::toSse)
                // 任何下游异常（如持久化失败）都包成一条 error 事件帧下发，
                // 避免“响应已提交后抛 UnsupportedOperationException”导致客户端收到一条死流、无任何提示。
                // 这里下发结构化 JSON（{"message","type"}），前端 chat.html 会解析并展示具体错误。
                .onErrorResume(e -> {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    String json = "{\"message\":" + escapeJson(msg)
                            + ",\"type\":" + escapeJson(e.getClass().getSimpleName()) + "}";
                    @SuppressWarnings("unchecked")
                    ServerSentEvent<AgentEvent> errEvent = (ServerSentEvent<AgentEvent>) (Object)
                            ServerSentEvent.builder().event("error").data(json).build();
                    return Flux.just(errEvent);
                });
    }

    /** 最小化的 JSON 字符串转义，仅用于 error 事件帧的 data 字段。 */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    @GetMapping(value = "/chat/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> stream(@PathVariable String conversationId) {
        return eventBus.flux(new ConversationId(conversationId)).map(this::toSse);
    }

    /** 列出某用户的历史会话（侧边栏）。 */
    @GetMapping(value = "/conversations")
    public Flux<ConversationAppService.ConversationSummary> listConversations(
            @RequestParam(defaultValue = "web-user") String userId) {
        return appService.listConversations(userId)
                .onErrorResume(e -> Flux.empty());   // DB 不可用时返回空列表，侧边栏显示“无历史”
    }

    /** 获取某会话的完整消息（点击历史项回填）。 */
    @GetMapping(value = "/conversations/{conversationId}")
    public Mono<ConversationAppService.ConversationDetail> getConversation(
            @PathVariable String conversationId) {
        return appService.getConversation(new ConversationId(conversationId))
                .onErrorResume(e -> Mono.empty());    // 找不到/DB 不可用时返回空
    }

    /** 将领域 AgentEvent 映射为具名 SSE 事件。 */
    private ServerSentEvent<AgentEvent> toSse(AgentEvent e) {
        String name = switch (e) {
            case AgentEvent.Thinking t    -> "thinking";
            case AgentEvent.ToolCalling t -> "tool_calling";
            case AgentEvent.ToolResult t  -> "tool_result";
            case AgentEvent.Token t       -> "token";
            case AgentEvent.Completed t   -> "completed";
        };
        return ServerSentEvent.builder(e).event(name).build();
    }

    /** 请求体：userId 必填；conversationId 可选（不传则新建会话）。 */
    public record ChatRequest(String userId, String message, String conversationId) {}
}
