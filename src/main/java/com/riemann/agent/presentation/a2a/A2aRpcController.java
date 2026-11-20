package com.riemann.agent.presentation.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.application.ConversationAppService;
import com.riemann.agent.auth.AuthenticatedAgent;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.infrastructure.a2a.A2aClientProperties;
import com.riemann.agent.presentation.a2a.protocol.Artifact;
import com.riemann.agent.presentation.a2a.protocol.JsonRpcError;
import com.riemann.agent.presentation.a2a.protocol.JsonRpcRequest;
import com.riemann.agent.presentation.a2a.protocol.JsonRpcResponse;
import com.riemann.agent.presentation.a2a.protocol.Message;
import com.riemann.agent.presentation.a2a.protocol.Part;
import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import com.riemann.agent.presentation.a2a.protocol.Task;
import com.riemann.agent.presentation.a2a.protocol.TaskArtifactUpdateEvent;
import com.riemann.agent.presentation.a2a.protocol.TaskState;
import com.riemann.agent.presentation.a2a.protocol.TaskStatus;
import com.riemann.agent.presentation.a2a.protocol.TaskStatusUpdateEvent;
import com.riemann.agent.presentation.a2a.protocol.TextPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A2A JSON-RPC 2.0 端点：POST /a2a。
 *
 * <p>用内容协商（Accept 头）区分两个处理方法，逻辑上仍是同一个 A2A 端点：
 *
 * <ul>
 *   <li>Accept: application/json → 处理 message/send、tasks/get、tasks/list、tasks/cancel、
 *       tasks/pushNotificationConfig/{set,get,delete}（普通 JSON 响应）。
 *   <li>Accept: text/event-stream → 处理 message/stream（SSE，每个事件是一个 JSON-RPC 响应对象）。
 * </ul>
 *
 * 复用 {@link ConversationAppService#chat} 的 Flux&lt;AgentEvent&gt;，将其映射为 A2A 的 Task / Artifact /
 * 流式事件。
 */
@RestController
public class A2aRpcController {

    /** 没有任何身份信息时的兜底 userId（所有匿名调用共享一个桶，仅用于本地调试）。 */
    private static final String ANONYMOUS_USER = "a2a:anonymous";

    /** Agent 在答案末尾输出该标记表示「信息不足，必须追问」；A2A 层据此进入 input-required。 */
    private static final String INPUT_MARKER = "[[A2A:INPUT_REQUIRED]]";

    /** 只注入到 A2A 请求的提示，不会进入 Task.history，也不会影响浏览器端对话。 模型只有在确实无法继续时才输出标记，避免正常回答被误判为等待输入。 */
    private static final String INPUT_HINT =
            "\n\n[A2A 协议指令] 仅当缺少关键信息、必须向对端追问才能继续任务时，"
                    + "在回答最后另起一行输出 "
                    + INPUT_MARKER
                    + " 并紧跟一个简短的追问问题；信息充分时不要输出该标记。";

    private final ConversationAppService appService;
    private final A2aTaskStore taskStore;
    private final A2aPushNotificationService pushService;
    private final ObjectMapper objectMapper;
    private final A2aClientProperties clientProperties;

    public A2aRpcController(
            ConversationAppService appService,
            A2aTaskStore taskStore,
            A2aPushNotificationService pushService,
            ObjectMapper objectMapper,
            A2aClientProperties clientProperties) {
        this.appService = appService;
        this.taskStore = taskStore;
        this.pushService = pushService;
        this.objectMapper = objectMapper;
        this.clientProperties = clientProperties;
    }

    // ===================== 普通 JSON 端点 =====================

    @PostMapping(
            value = "/a2a",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> handleJson(
            @RequestBody String body, ServerWebExchange exchange) {
        // The agent id was bound by A2aAuthenticationFilter after verifying the HMAC signature.
        // Never read the raw X-Agent-Id header here: it is an untrusted claim.
        String agentId = AuthenticatedAgent.require(exchange);
        JsonRpcRequest req = parseRequest(body);
        if (req == null) {
            return Mono.just(
                    ResponseEntity.ok(
                            json(
                                    JsonRpcResponse.error(
                                            null,
                                            JsonRpcError.of(
                                                    JsonRpcError.PARSE_ERROR, "Parse error")))));
        }
        if (req.method() == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_REQUEST, "Missing method"));
        }
        return switch (req.method()) {
            case "message/send" -> messageSend(req, agentId);
            case "tasks/get" -> tasksGet(req, agentId);
            case "tasks/list" -> tasksList(req, agentId);
            case "tasks/cancel" -> tasksCancel(req, agentId);
            case "tasks/pushNotificationConfig/set" -> pushConfigSet(req, agentId);
            case "tasks/pushNotificationConfig/get" -> pushConfigGet(req, agentId);
            case "tasks/pushNotificationConfig/delete" -> pushConfigDelete(req, agentId);
            case "message/stream" ->
                    Mono.just(
                            errorJson(
                                    req.id(),
                                    JsonRpcError.INVALID_REQUEST,
                                    "message/stream must use Accept: text/event-stream"));
            default ->
                    Mono.just(
                            errorJson(
                                    req.id(),
                                    JsonRpcError.METHOD_NOT_FOUND,
                                    "Method not found: " + req.method()));
        };
    }

    // ===================== SSE 流式端点 =====================

    @PostMapping(
            value = "/a2a",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> handleStream(
            @RequestBody String body, ServerWebExchange exchange) {
        String agentId = AuthenticatedAgent.require(exchange);
        JsonRpcRequest req = parseRequest(body);
        if (req == null) {
            return Flux.just(errorSse(null, JsonRpcError.PARSE_ERROR, "Parse error"));
        }
        if (!"message/stream".equals(req.method())) {
            return Flux.just(
                    errorSse(
                            req.id(),
                            JsonRpcError.INVALID_REQUEST,
                            "Use Accept: application/json for " + req.method()));
        }
        return messageStream(req, agentId);
    }

    // ===================== message/send =====================

    private Mono<ResponseEntity<String>> messageSend(JsonRpcRequest req, String agentId) {
        SendContext ctx = resolveContext(req, agentId);
        if (ctx == null) {
            return Mono.just(
                    errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.message"));
        }
        if (ctx.depth() > maxDepth()) {
            return Mono.just(
                    errorJson(req.id(), JsonRpcError.INVALID_PARAMS, depthMessage(ctx.depth())));
        }
        // 同一个 taskId 再次 send = input-required 后补参：把之前的 history 带回来，
        // 让对端看到完整的提问/追问/补充链路，而不是只有最后一条消息。
        List<Message> history = new ArrayList<>();
        taskStore
                .get(ctx.taskId(), agentId)
                .ifPresent(
                        previous -> {
                            if (previous.history() != null) {
                                history.addAll(previous.history());
                            }
                        });
        history.add(ctx.message());

        return appService
                .chat(
                        ctx.userId(),
                        ctx.text(),
                        new ConversationId(ctx.conversationId()),
                        ctx.attributes())
                .collectList()
                .map(
                        events -> {
                            StringBuilder sb = new StringBuilder();
                            for (AgentEvent e : events) {
                                if (e instanceof AgentEvent.Token t) {
                                    sb.append(t.delta());
                                }
                            }
                            Answer answer = parseAnswer(sb.toString());
                            Task task = buildTask(ctx, history, answer);
                            // 记录 conversationId 与已验签的 owner：后续 tasks/cancel 才能定位到
                            // 真正在跑的那一轮，且别的对端无法读到/取消这条任务
                            taskStore.put(task, ctx.conversationId(), agentId);
                            pushService.notify(task);
                            return ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), task)));
                        })
                .onErrorResume(
                        e -> {
                            failTask(ctx, history, agentId, e.getMessage());
                            return Mono.just(
                                    errorJson(
                                            req.id(), JsonRpcError.INTERNAL_ERROR, e.getMessage()));
                        });
    }

    // ===================== message/stream =====================

    private Flux<ServerSentEvent<String>> messageStream(JsonRpcRequest req, String agentId) {
        SendContext ctx = resolveContext(req, agentId);
        if (ctx == null) {
            return Flux.just(
                    errorSse(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.message"));
        }
        if (ctx.depth() > maxDepth()) {
            return Flux.just(
                    errorSse(req.id(), JsonRpcError.INVALID_PARAMS, depthMessage(ctx.depth())));
        }
        List<Message> history = new ArrayList<>();
        taskStore
                .get(ctx.taskId(), agentId)
                .ifPresent(
                        previous -> {
                            if (previous.history() != null) {
                                history.addAll(previous.history());
                            }
                        });
        history.add(ctx.message());
        AtomicReference<StringBuilder> acc = new AtomicReference<>(new StringBuilder());

        TaskStatusUpdateEvent started =
                new TaskStatusUpdateEvent(
                        ctx.contextId(), ctx.taskId(), TaskStatus.of(TaskState.working));

        Flux<Object> a2aEvents =
                appService
                        .chat(
                                ctx.userId(),
                                ctx.text(),
                                new ConversationId(ctx.conversationId()),
                                ctx.attributes())
                        .concatMap(
                                e -> {
                                    if (e instanceof AgentEvent.Token t) {
                                        acc.get().append(t.delta());
                                        // 标记是协议内部控制信号，不能作为产物下发给对端。
                                        String clean = t.delta().replace(INPUT_MARKER, "");
                                        if (clean.isBlank()) {
                                            return Mono.empty();
                                        }
                                        TaskArtifactUpdateEvent chunk =
                                                new TaskArtifactUpdateEvent(
                                                        ctx.contextId(),
                                                        ctx.taskId(),
                                                        Artifact.text(
                                                                "artifact-0",
                                                                clean,
                                                                0,
                                                                true,
                                                                false));
                                        return Mono.just((Object) chunk);
                                    } else if (e instanceof AgentEvent.Completed) {
                                        Answer answer = parseAnswer(acc.get().toString());
                                        Task task = buildTask(ctx, history, answer);
                                        taskStore.put(task, ctx.conversationId(), agentId);
                                        pushService.notify(task);
                                        TaskArtifactUpdateEvent last =
                                                TaskArtifactUpdateEvent.last(
                                                        ctx.contextId(),
                                                        ctx.taskId(),
                                                        Artifact.text(
                                                                "artifact-0",
                                                                answer.text(),
                                                                0,
                                                                false,
                                                                true));
                                        TaskStatus finalStatus =
                                                answer.question() == null
                                                        ? TaskStatus.of(TaskState.completed)
                                                        : TaskStatus.of(
                                                                TaskState.input_required,
                                                                answer.question());
                                        TaskStatusUpdateEvent done =
                                                TaskStatusUpdateEvent.last(
                                                        ctx.contextId(), ctx.taskId(), finalStatus);
                                        return Flux.just((Object) last, (Object) done);
                                    }
                                    return Mono.empty();
                                });

        return Flux.concat(Flux.just((Object) started), a2aEvents)
                .map(
                        ev ->
                                ServerSentEvent.<String>builder()
                                        .data(json(JsonRpcResponse.success(req.id(), ev)))
                                        .build())
                .onErrorResume(
                        e -> {
                            failTask(ctx, history, agentId, e.getMessage());
                            return Flux.just(
                                    errorSse(
                                            req.id(), JsonRpcError.INTERNAL_ERROR, e.getMessage()));
                        });
    }

    // ===================== tasks/get & tasks/cancel =====================

    private Mono<ResponseEntity<String>> tasksGet(JsonRpcRequest req, String owner) {
        String id =
                req.params() != null && req.params().has("id")
                        ? req.params().get("id").asText()
                        : null;
        if (id == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.id"));
        }
        return Mono.justOrEmpty(taskStore.get(id, owner))
                .map(task -> ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), task))))
                .defaultIfEmpty(
                        ResponseEntity.ok(
                                json(
                                        JsonRpcResponse.error(
                                                req.id(),
                                                JsonRpcError.of(
                                                        JsonRpcError.INVALID_PARAMS,
                                                        "Task not found: " + id)))));
    }

    /** 列出当前对端自己的任务，支持 contextId/state 过滤与游标分页。 */
    private Mono<ResponseEntity<String>> tasksList(JsonRpcRequest req, String owner) {
        JsonNode p = req.params();
        String contextId =
                p != null && p.hasNonNull("contextId") ? p.get("contextId").asText() : null;
        TaskState state = parseState(p == null ? null : p.get("state"));
        int pageSize = 50;
        if (p != null && p.hasNonNull("pageSize")) {
            pageSize = Math.max(1, Math.min(200, p.get("pageSize").asInt(50)));
        }
        int offset = 0;
        if (p != null && p.hasNonNull("pageToken")) {
            try {
                offset = Math.max(0, Integer.parseInt(p.get("pageToken").asText()));
            } catch (NumberFormatException ignored) {
                // 非法游标按第一页处理，不泄露内部状态
            }
        }
        List<Task> found = taskStore.list(owner, contextId, state, pageSize + 1, offset);
        boolean hasMore = found.size() > pageSize;
        List<Task> page = hasMore ? found.subList(0, pageSize) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", page);
        if (hasMore) {
            result.put("nextPageToken", String.valueOf(offset + pageSize));
        }
        return Mono.just(ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), result))));
    }

    // ===================== push notification config =====================

    private Mono<ResponseEntity<String>> pushConfigSet(JsonRpcRequest req, String owner) {
        try {
            String taskId = textParam(req, "taskId");
            JsonNode cfgNode =
                    req.params() == null ? null : req.params().get("pushNotificationConfig");
            if (taskId == null || cfgNode == null || cfgNode.isNull()) {
                return Mono.just(
                        errorJson(
                                req.id(),
                                JsonRpcError.INVALID_PARAMS,
                                "taskId and pushNotificationConfig are required"));
            }
            PushNotificationConfig config =
                    objectMapper.treeToValue(cfgNode, PushNotificationConfig.class);
            pushService.set(owner, taskId, config);
            return Mono.just(ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), config))));
        } catch (IllegalArgumentException e) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, e.getMessage()));
        } catch (Exception e) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INTERNAL_ERROR, e.getMessage()));
        }
    }

    private Mono<ResponseEntity<String>> pushConfigGet(JsonRpcRequest req, String owner) {
        try {
            String taskId = textParam(req, "taskId");
            String configId = textParam(req, "configId");
            if (taskId == null) {
                return Mono.just(
                        errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "taskId is required"));
            }
            return Mono.just(
                    pushService
                            .get(owner, taskId, configId)
                            .map(
                                    cfg ->
                                            ResponseEntity.ok(
                                                    json(JsonRpcResponse.success(req.id(), cfg))))
                            .orElseGet(
                                    () ->
                                            ResponseEntity.ok(
                                                    json(
                                                            JsonRpcResponse.error(
                                                                    req.id(),
                                                                    JsonRpcError.of(
                                                                            JsonRpcError
                                                                                    .INVALID_PARAMS,
                                                                            "Push config not found"))))));
        } catch (IllegalArgumentException e) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, e.getMessage()));
        }
    }

    private Mono<ResponseEntity<String>> pushConfigDelete(JsonRpcRequest req, String owner) {
        try {
            String taskId = textParam(req, "taskId");
            if (taskId == null) {
                return Mono.just(
                        errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "taskId is required"));
            }
            boolean deleted = pushService.delete(owner, taskId, textParam(req, "configId"));
            return Mono.just(
                    ResponseEntity.ok(
                            json(JsonRpcResponse.success(req.id(), Map.of("deleted", deleted)))));
        } catch (IllegalArgumentException e) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, e.getMessage()));
        }
    }

    /**
     * 取消任务 —— <b>真的取消</b>，不只是改状态。
     *
     * <p>改造前这里只把 taskStore 里的状态写成 {@code canceled}，而正在跑的那一轮完全不知情： Agent 的 ReAct 循环会继续把剩余迭代跑完，token
     * 照烧，直到 {@code turn-timeout} 才收手。 现在通过任务关联的 conversationId 反查，调用 {@link
     * ConversationAppService#cancelTurn}， 置位 AgentScope 的中断标志（下一次迭代开始处生效）。
     */
    private Mono<ResponseEntity<String>> tasksCancel(JsonRpcRequest req, String owner) {
        String id =
                req.params() != null && req.params().has("id")
                        ? req.params().get("id").asText()
                        : null;
        if (id == null) {
            return Mono.just(errorJson(req.id(), JsonRpcError.INVALID_PARAMS, "Missing params.id"));
        }
        String conversationId = taskStore.conversationIdOf(id, owner).orElse(null);
        boolean interrupted = conversationId != null && appService.cancelTurn(conversationId);

        return Mono.justOrEmpty(
                        taskStore.computeIfPresent(
                                id,
                                owner,
                                t ->
                                        new Task(
                                                t.id(),
                                                t.contextId(),
                                                TaskStatus.of(TaskState.canceled),
                                                t.artifacts(),
                                                t.history(),
                                                Map.of(
                                                        "canceled",
                                                        true,
                                                        "interrupted",
                                                        interrupted))))
                .map(
                        task -> {
                            pushService.notify(task);
                            return ResponseEntity.ok(json(JsonRpcResponse.success(req.id(), task)));
                        })
                .defaultIfEmpty(
                        ResponseEntity.ok(
                                json(
                                        JsonRpcResponse.error(
                                                req.id(),
                                                JsonRpcError.of(
                                                        JsonRpcError.INVALID_PARAMS,
                                                        "Task not found: " + id)))));
    }

    // ===================== 辅助 =====================

    /**
     * 从 params 解析出 userId / 文本 / conversationId / taskId / 委托跳数。
     *
     * <p><b>调用方身份</b>：优先取 {@code X-Agent-Id} 请求头（对端委托时由 {@code A2aClient} 写入）， 其次取
     * message.metadata.userId。改造前所有外部调用都落成同一个 {@code "a2a-user"}， 导致它们共享同一个「并发 2 / 日 50 万
     * token」的配额桶——一个对端打满，其余全部被拒。 现在按调用方分桶，并且能在日志/指标里区分是谁在调。
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
        // 只给 A2A 调用注入追问协议提示；浏览器端对话不受影响，Task.history 也只保留原消息。
        return new SendContext(
                userId,
                message.text() + INPUT_HINT,
                conversationId,
                taskId,
                contextId,
                message,
                depth);
    }

    /**
     * Force every A2A caller into the {@code a2a:} namespace. An A2A request must never be able to
     * choose a real user id, otherwise it could read that user's conversations.
     */
    static String resolveUserId(String agentId, Message message) {
        String candidate = (agentId != null && !agentId.isBlank()) ? agentId : null;
        if (candidate == null && message != null) {
            String fromMessage = message.userIdOrDefault(null);
            if (fromMessage != null && !fromMessage.isBlank()) {
                candidate = fromMessage;
            }
        }
        if (candidate == null) {
            return ANONYMOUS_USER;
        }
        String id = candidate.startsWith("a2a:") ? candidate.substring(4) : candidate;
        // Reject header injection / log forging and keep quota bucket ids stable.
        if (!id.matches("[A-Za-z0-9._-]+")) {
            return ANONYMOUS_USER;
        }
        return "a2a:" + id;
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

    /** 从模型回答中剥离内部控制标记；有标记即表示任务进入 input-required。 */
    static Answer parseAnswer(String raw) {
        String text = raw == null ? "" : raw;
        int marker = text.indexOf(INPUT_MARKER);
        if (marker < 0) {
            return new Answer(text, null);
        }
        String before = text.substring(0, marker).trim();
        String question = text.substring(marker + INPUT_MARKER.length()).trim();
        return new Answer(before, question.isBlank() ? "请补充必要信息后继续。" : question);
    }

    private Task buildTask(SendContext ctx, List<Message> history, Answer answer) {
        TaskStatus status =
                answer.question() == null
                        ? TaskStatus.of(TaskState.completed)
                        : TaskStatus.of(TaskState.input_required, answer.question());
        return new Task(
                ctx.taskId(),
                ctx.contextId(),
                status,
                List.of(Artifact.text("artifact-0", answer.text(), 0, false, true)),
                history,
                null);
    }

    /** 模型/工具异常时把任务落到 failed，并通知已注册的对端。 */
    private void failTask(SendContext ctx, List<Message> history, String owner, String error) {
        Task failed =
                new Task(
                        ctx.taskId(),
                        ctx.contextId(),
                        TaskStatus.of(TaskState.failed, error),
                        List.of(),
                        history,
                        null);
        taskStore.put(failed, ctx.conversationId(), owner);
        pushService.notify(failed);
    }

    private static String textParam(JsonRpcRequest req, String name) {
        JsonNode p = req.params();
        return p != null && p.hasNonNull(name) ? p.get(name).asText() : null;
    }

    private static TaskState parseState(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText();
        return raw == null || raw.isBlank() ? null : TaskState.from(raw);
    }

    record Answer(String text, String question) {}

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
        return ServerSentEvent.<String>builder()
                .data(json(JsonRpcResponse.error(id, JsonRpcError.of(code, msg))))
                .build();
    }

    /**
     * 一次 send/stream 请求解析出的上下文。
     *
     * @param depth 本次请求的委托跳数，用于拒绝过深的级联委托（A→B→C→…）
     */
    private record SendContext(
            String userId,
            String text,
            String conversationId,
            String taskId,
            String contextId,
            Message message,
            int depth) {

        /**
         * 透传给编排层的附加属性。
         *
         * <p>委托跳数必须一路传到 {@code RuntimeContext}，否则出站工具无法知道「自己是第几跳」， 深度限制就永远读到 0、形同虚设。
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
        return "委托层级过深（depth=" + depth + "，上限=" + maxDepth() + "），已拒绝本次级联委托，避免智能体之间循环调用。";
    }
}
