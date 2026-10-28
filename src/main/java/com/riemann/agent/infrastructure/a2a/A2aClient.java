package com.riemann.agent.infrastructure.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * 出站 A2A 客户端：以 JSON-RPC 2.0 调用对端智能体的 {@code message/send} 与 {@code tasks/cancel}。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>失败不上抛</b>：对端不可达/超时/返回 error 都收敛为 {@link DelegationResult#ok()} = false
 *       的结果对象，由工具层转成「模型能读懂的一句话」。委托失败不该让本机这一轮对话失败。</li>
 *   <li><b>超时双层</b>：这里按 {@code agent.a2a.client.timeout} 兜一次；
 *       整轮还有 {@code agent.execution.turn-timeout} 兜底，因此对端挂死不会拖住前端。</li>
 *   <li><b>只取文本</b>：从 {@code result.artifacts[].parts[].text} 拼接文本产物；
 *       文件/数据单元暂不支持（本项目的 Agent Card 也只声明了 text 模态）。</li>
 *   <li><b>深度透传</b>：{@code metadata.delegationDepth} 随请求带出，接收方据此判断是否还能继续委托，
 *       这是防止 A→B→A 无限 ping-pong 的关键（见 {@link A2aClientProperties#getMaxDelegationDepth()}）。</li>
 * </ul>
 */
@Component
public class A2aClient {

    private static final Logger log = LoggerFactory.getLogger(A2aClient.class);

    private final WebClient webClient = WebClient.create();
    private final ObjectMapper json = new ObjectMapper();
    private final A2aClientProperties props;

    public A2aClient(A2aClientProperties props) {
        this.props = props;
    }

    /** 一次委托的结果。 */
    public record DelegationResult(boolean ok, String text, String taskId, String state, String error) {

        static DelegationResult success(String text, String taskId, String state) {
            return new DelegationResult(true, text, taskId, state, null);
        }

        static DelegationResult failure(String error) {
            return new DelegationResult(false, null, null, null, error);
        }
    }

    /**
     * 把一段文本委托给对端智能体（{@code message/send}，一次性返回）。
     *
     * @param endpoint      对端的 A2A 端点（如 {@code http://10.0.0.5:8080/a2a}）
     * @param text          要委托的问题
     * @param contextId     对端侧会话标识；同一 contextId 的多轮会共享上下文
     * @param callerAgentId 调用方标识，写进 metadata 供对端做审计/限流
     * @param depth         本次委托的跳数（调用方深度 + 1）
     */
    public Mono<DelegationResult> sendMessage(String endpoint, String text, String contextId,
                                              String callerAgentId, int depth) {
        String body;
        try {
            body = json.writeValueAsString(buildSendRequest(text, contextId, callerAgentId, depth));
        } catch (Exception e) {
            return Mono.just(DelegationResult.failure("委托请求序列化失败：" + e.getMessage()));
        }
        Duration timeout = props.getTimeout();
        return webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Agent-Id", callerAgentId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(this::parseSendResponse)
                .onErrorResume(e -> {
                    log.warn("委托对端 {} 失败：{}", endpoint, e.toString());
                    return Mono.just(DelegationResult.failure(describeError(e, timeout)));
                });
    }

    /**
     * 取消对端的某个任务（{@code tasks/cancel}）。
     *
     * @return true 表示对端确认已取消
     */
    public Mono<Boolean> cancelTask(String endpoint, String taskId) {
        String body;
        try {
            ObjectNode req = json.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", UUID.randomUUID().toString());
            req.put("method", "tasks/cancel");
            req.putObject("params").put("id", taskId);
            body = json.writeValueAsString(req);
        } catch (Exception e) {
            return Mono.just(false);
        }
        return webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(props.getTimeout())
                .map(resp -> {
                    JsonNode node = read(resp);
                    JsonNode result = node.path("result");
                    return "canceled".equalsIgnoreCase(result.path("status").path("state").asText(""));
                })
                .onErrorResume(e -> {
                    log.warn("取消对端 {} 的任务 {} 失败：{}", endpoint, taskId, e.toString());
                    return Mono.just(false);
                });
    }

    // ===================== 内部 =====================

    private ObjectNode buildSendRequest(String text, String contextId, String callerAgentId, int depth) {
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", UUID.randomUUID().toString());
        req.put("method", "message/send");
        ObjectNode params = req.putObject("params");
        if (contextId != null && !contextId.isBlank()) {
            params.put("contextId", contextId);
        }
        ObjectNode message = params.putObject("message");
        message.put("role", "user");
        ArrayNode parts = message.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("kind", "text");
        part.put("text", text);
        // metadata 让对端知道「谁在调、第几跳」，是深度限制与审计的唯一凭据
        ObjectNode metadata = message.putObject("metadata");
        metadata.put("userId", "a2a:" + callerAgentId);
        metadata.put("callerAgentId", callerAgentId);
        metadata.put("delegationDepth", depth);
        return req;
    }

    /** 解析 {@code message/send} 响应：优先取 artifacts 文本，其次取 status.message。 */
    private DelegationResult parseSendResponse(String raw) {
        JsonNode node = read(raw);
        if (node.has("error")) {
            JsonNode err = node.get("error");
            return DelegationResult.failure("对端返回错误：" + err.path("message").asText(err.toString()));
        }
        JsonNode result = node.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return DelegationResult.failure("对端响应缺少 result 字段");
        }
        String taskId = result.path("id").asText(null);
        String state = result.path("status").path("state").asText("unknown");

        StringBuilder sb = new StringBuilder();
        for (JsonNode artifact : result.path("artifacts")) {
            for (JsonNode p : artifact.path("parts")) {
                String t = p.path("text").asText("");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
        }
        if (sb.length() == 0) {
            // 没产物时退化为状态消息，至少让模型知道对端做了什么
            String statusText = result.path("status").path("message").path("parts").findValuesAsText("text")
                    .stream().reduce((a, b) -> a + " " + b).orElse("");
            sb.append(statusText.isBlank() ? "（对端未返回文本产物，状态：" + state + "）" : statusText);
        }
        return DelegationResult.success(sb.toString(), taskId, state);
    }

    private JsonNode read(String raw) {
        try {
            return json.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static String describeError(Throwable e, Duration timeout) {
        String name = e.getClass().getSimpleName();
        if (name.contains("Timeout")) {
            return "委托超时（>" + timeout.toSeconds() + "s），对端可能正在忙或不可达";
        }
        String msg = e.getMessage() == null ? name : e.getMessage();
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}
