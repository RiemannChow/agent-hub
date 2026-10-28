package com.riemann.agent.presentation.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.application.ConversationAppService;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.infrastructure.a2a.A2aClientProperties;
import com.riemann.agent.presentation.a2a.protocol.Artifact;
import com.riemann.agent.presentation.a2a.protocol.JsonRpcError;
import com.riemann.agent.presentation.a2a.protocol.JsonRpcRequest;
import com.riemann.agent.presentation.a2a.protocol.JsonRpcResponse;
import com.riemann.agent.presentation.a2a.protocol.Message;
import com.riemann.agent.presentation.a2a.protocol.Part;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskArtifactUpdateEvent;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import com.riemann.agent.presentation.a2a.protocol.TaskStatusUpdateEvent;
import com.riemann.agent.presentation.a2a.protocol.TextPart;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A2A JSON-RPC 2.0 端点：POST /a2a。
 *
 * <p>用内容协商（Accept 头）区分两个处理方法，逻辑上仍是同一个 A2A 端点：
 * <ul>
 *   <li>Accept: application/json → 处理 message/send、tasks/get、tasks/cancel（普通 JSON 响应）。</li>
 *   <li>Accept: text/event-stream → 处理 message/stream（SSE，每个事件是一个 JSON-RPC 响应对象）。</li>
 * </ul>
 *
 * 复用 {@link ConversationAppService#chat} 的 Flux&lt;AgentEvent&gt;，将其映射为 A2A 的
 * Task / Artifact / 流式事件。
 */
@RestController
public class A2aRpcController {

    /** 调用方标识请求头：对端委托时由 {@code A2aClient} 写入，用于把不同调用方隔离到各自的配额桶。 */
    private static final String AGENT_ID_HEADER = "X-Agent-Id";
    /** 没有任何身份信息时的兜底 userId（所有匿名调用共享一个桶，仅用于本地调试）。 */
    private static final String ANONYMOUS_USER = "a2a-anonymous";

    private final ConversationAppService appService;
    private final A2aTaskStore taskStore;
    private final ObjectMapper objectMapper;
    private final A2aClientProperties clientProperties;

    public A2aRpcController(ConversationAppService appService, A2aTaskStore taskStore,
                            ObjectMapper objectMapper, A2aClientProperties clientProperties) {
        this.appService = appService;
        this.taskStore = taskStore;
        this.objectMapper = objectMapper;
        this.clientProperties = clientProperties;
    }

    // ===================== 普通 JSON 端点 =====================

    @PostMapping(value = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> handleJson(@RequestBody String body,
                                                   @RequestHeader(value = AGENT_ID_HEADER, required = false) String agentId) {
        JsonRpcRequest req = parseRequest(body);
        if (req == null) {
            return Mono.just(ResponseEntity.ok(json(JsonRpcResponse.error(null, JsonRpcError.of(JsonRpcError.PARSE_ERROR, "Parse error")))));
        }
        if (req.method() == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_REQUEST, "Missing method"));
        }
        return switch (req.method()) {
            case "message/send" -> messageSend(req, agentId);
            case "tasks/get" -> tasksGet(req);
            case "tasks/cancel" -> tasksCancel(req);
            case "message/stream" -> Mono.just(errorJson(req.id(), JsonRpcError.INVALID_REQUEST,
                    "message/stream must use Accept: text/event-stream"));
            default -> Mono.just(errorJson(req.id(), JsonRpcError.METHOD_NOT_FOUND, "Method not found: " + req.method()));
        };
    }

    // ===================== SSE 流式端点 =====================

    @PostMapping(value = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> handleStream(@RequestBody String body,
                                                      @RequestHeader(value = AGENT_ID_HEADER, required = false) String agentId) {
        JsonRpcRequest req = parseRequest(body);
        if (req == null) {
            return Flux.just(errorSse(null, JsonRpcError.PARSE_ERROR, "Parse error"));
        }
        if (!"message/stream".equals(req.method())) {
            return Flux.just(errorSse(req.id(), JsonRpcError.INVALID_REQUEST,
                    "Use Accept: application/json for " + req.method()));
        }
        return messageStream(req, agentId);
    }

    // ===================== message/send =====================

    private Mono<ResponseEntity<String>> messageSend(JsonRpcRequest req, String agentId) {
        SendContext ctx = resolveContext(req, agentId);
        if (ctx == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.message"));
        }
        if (ctx.depth() > maxDepth()) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, depthMessage(ctx.depth())));
        }
        return appService.chat(ctx.userId(), ctx.text(), new ConversationId(ctx.conversationId()), ctx.attributes())
                .collectList()
                .map(events -> {
                    StringBuilder sb = new StringBuilder();
                    for (AgentEvent e : events) {
                        if (e instanceof AgentEvent.Token t) {
                            sb.append(t.delta());
                        }
                    }
                    Task task = buildCompletedTask(ctx.taskId(), ctx.contextId(), ctx.message(), sb.toString());
                    // 记录 conversationId：这样后续 tasks/cancel 才能定位到真正在跑的那一轮
                    taskStore.put(task, ctx.conversationId());
                    return ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), task)));
                })
                .onErrorResume(e -> Mono.just(errorJson(req.id(), JsonRpcError.INTERNAL_ERROR, e.getMessage())));
    }

    // ===================== message/stream =====================

    private Flux<ServerSentEvent<String>> messageStream(JsonRpcRequest req, String agentId) {
        SendContext ctx = resolveContext(req, agentId);
        if (ctx == null) {
            return Flux.just(errorSse(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.message"));
        }
        if (ctx.depth() > maxDepth()) {
            return Flux.just(errorSse(req.id(), JsonRpcError.INVALID_PARAMS, depthMessage(ctx.depth())));
        }
        AtomicReference<StringBuilder> acc = new AtomicReference<>(new StringBuilder());

        TaskStatusUpdateEvent started = new TaskStatusUpdateEvent(ctx.contextId(), ctx.taskId(), TaskStatus.of(TaskState.working));

        Flux<Object> a2aEvents = appService.chat(ctx.userId(), ctx.text(),
                        new ConversationId(ctx.conversationId()), ctx.attributes())
                .concatMap(e -> {
                    if (e instanceof AgentEvent.Token t) {
                        acc.get().append(t.delta());
                        TaskArtifactUpdateEvent chunk = new TaskArtifactUpdateEvent(
                                ctx.contextId(), ctx.taskId(),
                                Artifact.text("artifact-0", t.delta(), 0, true, false));
                        return Mono.just((Object) chunk);
                    } else if (e instanceof AgentEvent.Completed) {
                        String full = acc.get().toString();
                        TaskArtifactUpdateEvent last = new TaskArtifactUpdateEvent(
                                ctx.contextId(), ctx.taskId(),
                                Artifact.text("artifact-0", full, 0, false, true));
                        TaskStatusUpdateEvent done = new TaskStatusUpdateEvent(ctx.contextId(), ctx.taskId(), TaskStatus.of(TaskState.completed));
                        taskStore.put(buildCompletedTask(ctx.taskId(), ctx.contextId(), ctx.message(), full),
                                ctx.conversationId());
                        return Flux.just((Object) last, (Object) done);
                    }
                    return Mono.empty();
                });

        return Flux.concat(Flux.just((Object) started), a2aEvents)
                .map(ev -> ServerSentEvent.<String>builder().data(json(JsonRpcResponse.success(req.id(), ev))).build())
                .onErrorResume(e -> Flux.just(errorSse(req.id(), JsonRpcError.INTERNAL_ERROR, e.getMessage())));
    }

    // ===================== tasks/get & tasks/cancel =====================

    private Mono<ResponseEntity<String>> tasksGet(JsonRpcRequest req) {
        String id = req.params() != null && req.params().has("id") ? req.params().get("id").asText() : null;
        if (id == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.id"));
        }
        return Mono.justOrEmpty(taskStore.get(id))
                .map(task -> ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), task))))
                .defaultIfEmpty(ResponseEntity.ok(json(JsonRpcResponse.error(req.id(),
                        JsonRpcError.of(JsonRpcError.INVALID_PARAMS, "Task not found: " + id)))));
    }

    /**
     * 取消任务 —— <b>真的取消</b>，不只是改状态。
     *
     * <p>改造前这里只把 taskStore 里的状态写成 {@code canceled}，而正在跑的那一轮完全不知情：
     * Agent 的 ReAct 循环会继续把剩余迭代跑完，token 照烧，直到 {@code turn-timeout} 才收手。
     * 现在通过任务关联的 conversationId 反查，调用 {@link ConversationAppService#cancelTurn}，
     * 置位 AgentScope 的中断标志（下一次迭代开始处生效）。</p>
     */
    private Mono<ResponseEntity<String>> tasksCancel(JsonRpcRequest req) {
        String id = req.params() != null && req.params().has("id") ? req.params().get("id").asText() : null;
        if (id == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.id"));
        }
        String conversationId = taskStore.conversationIdOf(id).orElse(null);
        boolean interrupted = conversationId != null && appService.cancelTurn(conversationId);

        return Mono.justOrEmpty(taskStore.computeIfPresent(id, t -> new Task(
                        t.id(), t.contextId(), TaskStatus.of(TaskState.canceled),
                        t.artifacts(), t.history(),
                        Map.of("canceled", true, "interrupted", interrupted))))
                .map(task -> ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), task))))
                .defaultIfEmpty(ResponseEntity.ok(json(JsonRpcResponse.error(req.id(),
                        JsonRpcError.of(JsonRpcError.INVALID_PARAMS, "Task not found: " + id)))));
    }

    // ===================== 辅助 =====================

    /**
     * 从 params 解析出 userId / 文本 / conversationId / taskId / 委托跳数。
     *
     * <p><b>调用方身份</b>：优先取 {@code X-Agent-Id} 请求头（对端委托时由 {@code A2aClient} 写入），
     * 其次取 message.metadata.userId。改造前所有外部调用都落成同一个 {@code "a2a-user"}，
     * 导致它们共享同一个「并发 2 / 日 50 万 token」的配额桶——一个对端打满，其余全部被拒。
     * 现在按调用方分桶，并且能在日志/指标里区分是谁在调。</p>
     */
    private SendContext resolveContext(JsonRpcRequest req, String agentId) {
        if (req.params() == null || !req.params().has("message")) {
            return null;
        }
        Message message = parseMessage(req.params().get("message"));
        if (message == null || message.text().isBlank()) {
            return null;
        }
        JsonNode params = req.params();
        String contextId = params.has("contextId") ? params.get("contextId").asText() : null;
        String taskId = params.has("id") ? params.get("id").asText() : null;
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
        }
        String conversationId = "a2a-" + (contextId != null ? contextId : taskId);

        String userId = resolveUserId(agentId, message);
        int depth = resolveDepth(message);
        return new SendContext(userId, message.text(), conversationId, taskId, contextId, message, depth);
    }

    /** 身份优先级：请求头 X-Agent-Id > message.metadata.userId > 匿名。 */
    private static String resolveUserId(String agentId, Message message) {
        if (agentId != null && !agentId.isBlank()) {
            return "a2a:" + agentId;
        }
        String fromMessage = message.userIdOrDefault(null);
        if (fromMessage != null && !fromMessage.isBlank()) {
            return fromMessage.startsWith("a2a:") ? fromMessage : "a2a:" + fromMessage;
        }
        return ANONYMOUS_USER;
    }

    /** 委托跳数：来自 message.metadata.delegationDepth（缺省 0 = 外部直接调用）。 */
    private static int resolveDepth(Message message) {
        if (message.metadata() == null) {
            return 0;
        }
        Object raw = message.metadata().get("delegationDepth");
        if (raw instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (raw instanceof String s) {
            try {
                return Math.max(0, Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private Message parseMessage(JsonNode node) {
        try {
            String role = node.path("role").asText("user");
            List<Part> parts = new ArrayList<>();
            JsonNode partsNode = node.get("parts");
            if (partsNode != null && partsNode.isArray()) {
                for (JsonNode p : partsNode) {
                    if (p.has("text")) {
                        parts.add(new TextPart(p.get("text").asText()));
                    }
                }
            }
            Map<String, Object> metadata = null;
            if (node.has("metadata") && node.get("metadata").isObject()) {
                metadata = objectMapper.convertValue(node.get("metadata"), Map.class);
            }
            return new Message(role, parts, metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private Task buildCompletedTask(String taskId, String contextId, Message message, String text) {
        Artifact artifact = Artifact.text("artifact-0", text, 0, false, true);
        return new Task(taskId, contextId, TaskStatus.of(TaskState.completed),
                List.of(artifact), List.of(message), null);
    }

    private JsonRpcRequest parseRequest(String body) {
        try {
            return objectMapper.readValue(body, JsonRpcRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal serialization error\"}}";
        }
    }

    private ResponseEntity<String> errorJson(Object id, int code, String msg) {
        return ResponseEntity.ok(json(JsonRpcResponse.error(id, JsonRpcError.of(code, msg))));
    }

    private ServerSentEvent<String> errorSse(Object id, int code, String msg) {
        return ServerSentEvent.<String>builder().data(json(JsonRpcResponse.error(id, JsonRpcError.of(code, msg)))).build();
    }

    /**
     * 一次 send/stream 请求解析出的上下文。
     *
     * @param depth 本次请求的委托跳数，用于拒绝过深的级联委托（A→B→C→…）
     */
    private record SendContext(String userId, String text, String conversationId, String taskId,
                               String contextId, Message message, int depth) {

        /**
         * 透传给编排层的附加属性。
         *
         * <p>委托跳数必须一路传到 {@code RuntimeContext}，否则出站工具无法知道「自己是第几跳」，
         * 深度限制就永远读到 0、形同虚设。</p>
         */
        Map<String, Object> attributes() {
            return depth == 0 ? Map.of() : Map.of("delegationDepth", depth);
        }
    }

    /** 允许的最大委托跳数（与出站侧共用同一配置，保证两端对「多深算太深」的判断一致）。 */
    private int maxDepth() {
        return Math.max(1, clientProperties.getMaxDelegationDepth());
    }

    private String depthMessage(int depth) {
        return "委托层级过深（depth=" + depth + "，上限=" + maxDepth()
                + "），已拒绝本次级联委托，避免智能体之间循环调用。";
    }
}
