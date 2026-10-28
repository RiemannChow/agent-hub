package com.riemann.agent.presentation;

import com.riemann.agent.application.ConversationAppService;
import com.riemann.agent.application.quota.QuotaExceededException;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

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
        // 关键：保留上游订阅句柄。之前只 subscribe 不持有 Disposable，客户端断开时
        // 被取消的只是 sink 的下游，上游仍在跑——Agent 的 ReAct 循环会继续烧 token。
        Disposable upstream = events.subscribe(sink::tryEmitNext, sink::tryEmitError, sink::tryEmitComplete);

        return sink.asFlux()
                .map(this::toSse)
                // 任何下游异常（如持久化失败、配额超限）都包成一条 error 事件帧下发，
                // 避免“响应已提交后抛 UnsupportedOperationException”导致客户端收到一条死流、无任何提示。
                // 这里下发结构化 JSON（{"message","type","code"}），前端 chat.html 会解析并展示具体错误。
                .onErrorResume(e -> {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    String code = errorCode(e);
                    StringBuilder json = new StringBuilder("{\"message\":").append(escapeJson(msg))
                            .append(",\"type\":").append(escapeJson(e.getClass().getSimpleName()));
                    if (code != null) {
                        json.append(",\"code\":").append(escapeJson(code));
                    }
                    json.append('}');
                    if (e instanceof QuotaExceededException quota) {
                        log.warn("会话 {} 被配额拦截：{}", id.value(), quota.kind());
                    }
                    @SuppressWarnings("unchecked")
                    ServerSentEvent<AgentEvent> errEvent = (ServerSentEvent<AgentEvent>) (Object)
                            ServerSentEvent.builder().event("error").data(json.toString()).build();
                    return Flux.just(errEvent);
                })
                // 客户端断开（关标签页/网关超时/前端点停止后 abort）→ CANCEL：
                // 1) dispose 上游，停止继续向 sink 灌事件；
                // 2) 通知编排层中断本轮，让 Agent 不再发起下一轮推理；
                // 3) 清理该会话的 sink，避免内存泄漏。
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        upstream.dispose();
                        boolean cancelled = appService.cancelTurn(id.value());
                        eventBus.remove(id, sink);
                        log.info("客户端断开，已取消会话 {} 的当前轮次（interrupted={}）", id.value(), cancelled);
                    }
                });
    }

    /**
     * 主动停止某会话当前正在生成的一轮（前端「停止生成」按钮）。
     *
     * <p>只置位中断标志，不 dispose 流——这样客户端仍能收到本轮已有的输出与结束事件，
     * 体验上是「停下来」而不是「连接被掐断」。</p>
     */
    @PostMapping("/chat/{conversationId}/stop")
    public Mono<Map<String, Object>> stop(@PathVariable String conversationId) {
        boolean cancelled = appService.cancelTurn(conversationId);
        return Mono.just(Map.of(
                "conversationId", conversationId,
                "cancelled", cancelled,
                "note", cancelled ? "已请求中断，当前这次模型/工具调用返回后即停止"
                        : "该会话当前没有正在生成的轮次"));
    }

    /** 把领域异常翻译成前端可判别的错误码（前端据此决定是提示「稍后再试」还是「明天再来」）。 */
    private static String errorCode(Throwable e) {
        if (e instanceof QuotaExceededException quota) {
            return switch (quota.kind()) {
                case CONCURRENCY -> "quota_concurrency";
                case DAILY_TOKENS -> "quota_daily_tokens";
            };
        }
        return null;
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
