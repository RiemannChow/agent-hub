package com.riemann.agent.infrastructure.a2a;

import com.riemann.agent.infrastructure.nacos.DiscoveredAgent;
import com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext;
import com.riemann.agent.presentation.a2a.protocol.AgentSkill;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把「一个对端 A2A 智能体」包装成模型可调用的工具 —— 这就是「多 Agent 协作」从目录变成能力的那个转换点。
 *
 * <h3>为什么用 {@link AgentTool} 而不是 {@code @Tool} 注解</h3>
 * <p>{@code @Tool} 是「编译期已知、方法签名固定」的注册方式，而这里的工具集合取决于运行时从 Nacos
 * 发现了谁——数量、名字、描述都不确定，必须动态注册。{@code Toolkit.registerAgentTool(AgentTool)}
 * 正好接受这种「自描述 + 自执行」的对象：{@code getName/getDescription/getParameters} 决定 schema，
 * {@code callAsync} 决定执行。</p>
 *
 * <h3>三个刻意的设计决定</h3>
 * <ol>
 *   <li><b>{@code isReadOnly() = true}</b>：委托不会改动本机任何状态，声明只读后框架可以放心并行调度，
 *       也不会因为「疑似有副作用」而限制重试。</li>
 *   <li><b>参数只有一个 {@code message}</b>：对端<b>看不到本机对话历史</b>（我们只把一段文本发过去），
 *       所以参数描述里明确要求「自包含、带必要上下文」，否则对端会答非所问。</li>
 *   <li><b>失败返回文本而不是抛异常</b>：对端宕机/超时是常态，模型应该看到「委托失败，原因是 X」
 *       然后自己决定换路（用本机工具或直接回答），而不是让整轮对话炸掉。</li>
 * </ol>
 */
public class RemoteAgentTool implements AgentTool {

    private final DiscoveredAgent peer;
    private final A2aClient client;
    private final A2aClientProperties props;
    private final String toolName;

    public RemoteAgentTool(DiscoveredAgent peer, String toolName, A2aClient client, A2aClientProperties props) {
        this.peer = peer;
        this.toolName = toolName;
        this.client = client;
        this.props = props;
    }

    /** 该工具对应的对端（注册器据此做增量比对）。 */
    public DiscoveredAgent peer() {
        return peer;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("把问题委托给对端智能体「").append(safe(peer.name())).append("」。");
        if (peer.card() != null && peer.card().description() != null && !peer.card().description().isBlank()) {
            sb.append("它的领域：").append(trim(peer.card().description(), 160)).append("。");
        }
        List<AgentSkill> skills = peer.card() == null ? null : peer.card().skills();
        if (skills != null && !skills.isEmpty()) {
            sb.append("可用技能：")
                    .append(skills.stream().map(AgentSkill::name).limit(6).collect(Collectors.joining("、")))
                    .append("。");
        }
        sb.append("适用场景：问题明显属于它的领域，或本机工具检索不到、答不了时。")
                .append("不适用：能用本机工具（SQL 问数 / 图书检索 / 知识图谱）直接回答的问题，不要委托。")
                .append("注意：对端看不到本机对话历史，message 必须自包含（带上必要的口径、时间范围、对象）。");
        return sb.toString();
    }

    /**
     * 单参数 JSON-Schema。
     *
     * <p>这里刻意只暴露一个必填的 {@code message}：委托场景下让模型去填 5 个结构化字段，
     * 它会倾向于编造（和 {@code seek_book_search} 的可选参数问题同源）。</p>
     */
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "string");
        message.put("description", "要委托给对端的完整问题。对端看不到本机对话历史，"
                + "请把必要的上下文、口径、时间范围一并写进来。");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", message);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("message"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 委托不改动本机状态，声明只读以便框架并行调度。 */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param.getToolUseBlock();
        String message = extractMessage(param);
        if (message.isBlank()) {
            return Mono.just(result(use, ToolResultState.ERROR,
                    "缺少 message 参数：请把要委托的问题写进 message（对端看不到本机对话历史）。"));
        }

        RuntimeContext rc = param.getRuntimeContext();
        AgentCallContext ctx = rc == null ? null : rc.get(AgentCallContext.class);
        int depth = ctx == null ? 0 : ctx.delegationDepth();
        int nextDepth = depth + 1;
        if (nextDepth > props.getMaxDelegationDepth()) {
            // 委托环保护：A→B→C→... 每跳 +1，超过上限就不再往外委托
            return Mono.just(result(use, ToolResultState.ERROR,
                    "已达到最大委托层级（" + props.getMaxDelegationDepth() + " 跳），请用本机工具或已有信息直接作答。"));
        }

        // contextId 复用本机会话 id：同一会话多次委托同一个对端时，对端侧能保持连续上下文
        String contextId = ctx == null ? null : ctx.conversationId();
        String callerAgentId = ctx == null ? "agent-hub" : "agent-hub:" + ctx.conversationId();

        return client.sendMessage(peer.a2aEndpoint(), message, contextId, callerAgentId, nextDepth)
                .map(r -> {
                    if (!r.ok()) {
                        return result(use, ToolResultState.ERROR,
                                "委托给「" + safe(peer.name()) + "」失败：" + r.error()
                                        + "。你可以改用本机工具，或直接说明无法回答。");
                    }
                    String text = truncate(r.text(), props.getMaxResultChars());
                    return result(use, ToolResultState.SUCCESS,
                            "【来自对端智能体「" + safe(peer.name()) + "」的回答】\n" + text
                                    + "\n（以上内容由对端生成，请基于它作答，不要编造对端没说的信息。）");
                })
                .onErrorResume(e -> Mono.just(result(use, ToolResultState.ERROR,
                        "委托调用异常：" + e.getMessage())));
    }

    // ===================== 内部 =====================

    @SuppressWarnings("unchecked")
    private static String extractMessage(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        if (input == null && param.getToolUseBlock() != null) {
            input = param.getToolUseBlock().getInput();
        }
        if (input == null) {
            return "";
        }
        Object raw = input.get("message");
        if (raw == null) {
            // 模型偶尔会把参数名写错，退化取唯一值，避免无谓失败
            if (input.size() == 1) {
                raw = input.values().iterator().next();
            } else {
                return "";
            }
        }
        return String.valueOf(raw).trim();
    }

    private static ToolResultBlock result(ToolUseBlock use, ToolResultState state, String text) {
        ToolResultBlock.Builder builder = ToolResultBlock.builder()
                .output(TextBlock.builder().text(text).build())
                .state(state);
        if (use != null) {
            builder.id(use.getId()).name(use.getName());
        }
        return builder.build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, max) + "…（结果过长已截断）";
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "未命名对端" : s;
    }

    /** 供注册器做「同一对端、同一端点」比对（避免每 15s 反复注册/注销）。 */
    public String fingerprint() {
        return peer.a2aEndpoint() + "|" + peer.name();
    }

    /** 便于日志排查。 */
    public List<String> debugInfo() {
        List<String> info = new ArrayList<>();
        info.add(toolName);
        info.add(peer.a2aEndpoint());
        return info;
    }
}
