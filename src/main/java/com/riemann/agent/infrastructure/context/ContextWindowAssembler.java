package com.riemann.agent.infrastructure.context;

import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.Message;
import com.riemann.agent.infrastructure.metrics.TurnTelemetry;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文窗口组装器：<b>预算窗口 + 滚动摘要</b>，替代原先写死的「最近 8 条硬截断」。
 *
 * <h3>为什么要改</h3>
 * <p>原来的 {@code MAX_HISTORY = 8} 有三个问题：</p>
 * <ol>
 *   <li><b>按条数不按体积</b>：一条长表格消息就能占满上下文，8 条短消息却几乎不占；</li>
 *   <li><b>丢掉的历史不可恢复</b>：第 3 轮说过的口径到第 9 轮直接消失，模型只能重新问；</li>
 *   <li><b>AgentScope 内置的摘要事件用不上</b>：框架的 {@code PreSummaryEvent / PostSummaryEvent /
 *       SummaryChunkEvent} 只在 {@code ReActAgent} 自身迭代耗尽（{@code iteration >= maxIters}）时触发，
 *       不覆盖「跨轮上下文压缩」这个场景——所以这里在领域层自己做。</li>
 * </ol>
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>取「本轮用户消息」之前的用户/助手消息（工具调用/结果属于内部细节，不入模型上下文）；</li>
 *   <li>从尾部倒着走，按<b>条数上限</b>与<b>字符预算</b>双重约束，得到窗口起点 {@code start}；</li>
 *   <li>{@code turns[0, start)} 就是「被挤出窗口的老历史」：
 *     <ul>
 *       <li>老历史不足 {@code summary-trigger-messages} 条 → 直接丢（省一次模型调用）；</li>
 *       <li>已有缓存摘要、且距上次摘要只新增了不到 {@code summary-min-new-messages} 条 →
 *           复用旧摘要，把这小段重新纳入窗口（既不重复摘要，也不丢内容）；</li>
 *       <li>否则做<b>增量摘要</b>：把「旧摘要 + 新增老历史」合并成新摘要并写缓存。</li>
 *     </ul>
 *   </li>
 *   <li>拼装：{@code [前置说明(反锚定 + 长期记忆 + 历史摘要)] + [最近窗口] + [本轮用户消息]}。</li>
 * </ol>
 *
 * <h3>失败降级</h3>
 * <p>摘要调用超时或报错时，<b>绝不阻塞本轮对话</b>：退回「只有最近窗口」的老行为，
 * 并记一条 warn。摘要是锦上添花，不是主链路依赖。</p>
 */
@Component
public class ContextWindowAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowAssembler.class);

    /** 反锚定说明：Hunyuan 容易把历史窗口里最早一条消息当成真实任务，必须显式纠正。 */
    private static final String ANTI_ANCHORING =
            "【上下文约束】下面先是“历史窗口”（仅作背景参考，其中的结论/过滤条件可能已过期），"
            + "最后一条“【本轮用户消息】”才是你本轮真正要处理的请求。\n"
            + "你必须依据【本轮用户消息】重新调用工具检索/问数，禁止把历史窗口里的旧结论、旧过滤条件"
            + "（genre / 评分 / 价格 / 评论数等）直接当作本轮答案；若历史窗口与本轮消息冲突，一律以本轮消息为准。\n";

    private static final String SUMMARY_HEADER = "【历史摘要】（更早的对话已被压缩，仅保留要点）\n";

    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是对话摘要器。任务是把「已有摘要」和「新增对话」合并成一段紧凑的中文摘要，供后续对话继续使用。\n"
            + "必须保留：用户的目标与偏好、已确认的事实与结论、关键的过滤条件与统计口径、"
            + "已经调用过的工具及其重要结果（数字、表名、字段名）。\n"
            + "必须丢弃：寒暄、重复表述、工具原始输出的细节、模型的思考过程。\n"
            + "只输出摘要正文：不要前言、不要标题、不要 Markdown 代码块、不要解释你在做什么。";

    private final ContextWindowProperties props;
    private final Model model;
    private final TurnTelemetry telemetry;

    /**
     * 会话 → 已摘要进度。LRU 上限由 {@code agent.context.summary-cache-size} 控制，
     * 防止长期运行的多用户场景下无限增长。
     */
    private final Map<String, CachedSummary> cache;

    public ContextWindowAssembler(ContextWindowProperties props, Model model, TurnTelemetry telemetry) {
        this.props = props;
        this.model = model;
        this.telemetry = telemetry;
        int max = Math.max(16, props.getSummaryCacheSize());
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedSummary> eldest) {
                return size() > max;
            }
        });
    }

    /** 历史中的一条对话（已过滤掉工具消息）。 */
    private record Turn(boolean user, String text) {
        String render() {
            return (user ? "用户：" : "助手：") + text;
        }
    }

    /** 摘要缓存项：{@code covered} 表示「前 covered 条 Turn 已被压进 {@code text}」。 */
    private record CachedSummary(int covered, String text) {
    }

    /**
     * 组装一次模型上下文。
     *
     * @param conversation  会话（含完整消息历史）
     * @param memoryCtx     召回的长期记忆块，可为空
     * @param antiAnchoring 是否注入反锚定说明（seekdb 的问数场景需要，纯知识问答不需要）
     */
    public Mono<ContextWindow> assemble(Conversation conversation, String memoryCtx, boolean antiAnchoring) {
        List<Message> msgs = conversation.messages();

        // 1) 定位本轮任务 = 最后一条用户消息
        int lastUserIdx = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof Message.UserMessage) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            // 没有用户消息（空会话/纯工具记录）：只发空串占位，保持行为可预期
            return Mono.just(new ContextWindow(List.of(new UserMessage("")), false, 0, 0));
        }
        String current = ((Message.UserMessage) msgs.get(lastUserIdx)).content();

        // 2) 历史 = 本轮之前的用户/助手消息
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < lastUserIdx; i++) {
            Message m = msgs.get(i);
            if (m instanceof Message.UserMessage um) {
                turns.add(new Turn(true, nullToEmpty(um.content())));
            } else if (m instanceof Message.AssistantMessage am) {
                turns.add(new Turn(false, nullToEmpty(am.content())));
            }
            // ToolCall / ToolResult 为工具内部细节，不入模型上下文
        }

        // 3) 预算窗口：条数 + 字符双上限
        int start = windowStart(turns);

        if (!props.isSummarizeEnabled() || start <= 0) {
            // 无溢出（或摘要关闭）：退化为「只保留最近窗口」
            return Mono.just(build(turns, start, "", memoryCtx, antiAnchoring, current));
        }

        String conversationId = conversation.id().value();
        CachedSummary cached = cache.get(conversationId);

        // 4) 溢出部分不足触发阈值、且此前也没摘要过 → 直接丢，不值得为几条历史花一次模型调用
        if (cached == null && start < Math.max(1, props.getSummaryTriggerMessages())) {
            return Mono.just(build(turns, start, "", memoryCtx, antiAnchoring, current));
        }

        int covered = (cached == null) ? 0 : Math.min(cached.covered(), start);
        int minNew = Math.max(1, props.getSummaryMinNewMessages());

        // 5) 距上次摘要新增不多：复用旧摘要，把这一小段留在窗口里（窗口略微变大，但不丢内容）
        if (cached != null && covered > 0 && start - covered < minNew) {
            return Mono.just(build(turns, covered, cached.text(), memoryCtx, antiAnchoring, current));
        }

        // 6) 增量摘要：[covered, start) 这一段压进旧摘要
        List<Turn> delta = turns.subList(covered, start);
        return summarize(conversation.userId(), conversationId, cached == null ? "" : cached.text(), delta)
                .map(text -> {
                    if (text.isBlank()) {
                        // 摘要失败降级：保持老行为（丢掉溢出部分），并复用可能存在的旧摘要
                        String fallback = cached == null ? "" : cached.text();
                        int keepFrom = fallback.isBlank() ? start : covered;
                        return build(turns, keepFrom, fallback, memoryCtx, antiAnchoring, current);
                    }
                    cache.put(conversationId, new CachedSummary(start, text));
                    return build(turns, start, text, memoryCtx, antiAnchoring, current);
                });
    }

    /**
     * 从尾部倒推窗口起点：最多 {@code recent-messages} 条，且累计字符不超过 {@code recent-char-budget}。
     * 至少保留 1 条（哪怕它单条就超预算），否则模型会完全看不到上一轮。
     */
    private int windowStart(List<Turn> turns) {
        int maxCount = Math.max(0, props.getRecentMessages());
        int maxChars = Math.max(0, props.getRecentCharBudget());
        int count = 0;
        int chars = 0;
        int i = turns.size() - 1;
        for (; i >= 0; i--) {
            Turn t = turns.get(i);
            int len = t.text().length();
            if (count >= maxCount) break;
            if (count > 0 && maxChars > 0 && chars + len > maxChars) break;
            chars += len;
            count++;
        }
        return i + 1;
    }

    /** 拼装最终的 Msg 列表。 */
    private ContextWindow build(List<Turn> turns, int from, String summary,
                                String memoryCtx, boolean antiAnchoring, String current) {
        StringBuilder prefix = new StringBuilder();
        if (antiAnchoring) {
            prefix.append(ANTI_ANCHORING);
        }
        if (memoryCtx != null && !memoryCtx.isBlank()) {
            prefix.append(memoryCtx).append('\n');
        }
        if (summary != null && !summary.isBlank()) {
            prefix.append(SUMMARY_HEADER).append(summary).append('\n');
        }

        List<Msg> messages = new ArrayList<>();
        int kept = 0;
        for (int i = from; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (t.text().isBlank()) continue;
            messages.add(t.user() ? new UserMessage(t.text()) : new AssistantMessage(t.text()));
            kept++;
        }
        // 本轮任务永远置于末尾（前缀里带反锚定说明/长期记忆/历史摘要）
        String taskHeader = (antiAnchoring || prefix.length() > 0) ? "\n【本轮用户消息】\n" : "";
        messages.add(new UserMessage(prefix + taskHeader + current));

        boolean summarized = summary != null && !summary.isBlank();
        return new ContextWindow(messages, summarized, summarized ? from : 0, kept);
    }

    /**
     * 增量摘要：把「已有摘要 + 新增老历史」交给模型合并成新摘要。
     *
     * <p>任何异常（超时、鉴权失败、网关 5xx）都收敛为空串，由调用方降级处理——
     * 摘要失败绝不能让这一轮对话失败。</p>
     */
    private Mono<String> summarize(String userId, String conversationId, String previous, List<Turn> delta) {
        if (delta.isEmpty()) {
            return Mono.just(previous);
        }
        StringBuilder body = new StringBuilder();
        if (previous != null && !previous.isBlank()) {
            body.append("【已有摘要】\n").append(previous).append("\n\n");
        }
        body.append("【新增对话】\n");
        for (Turn t : delta) {
            body.append(t.render()).append('\n');
        }
        body.append("\n请输出合并后的摘要（不超过 ").append(props.getSummaryMaxChars()).append(" 字）。");

        List<Msg> prompt = List.of(
                new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                new UserMessage(body.toString()));

        GenerateOptions options = GenerateOptions.builder()
                .stream(true)
                .temperature(0.2)
                .maxTokens(Math.min(4096, Math.max(256, props.getSummaryMaxChars() * 2)))
                .build();

        // 摘要是一次独立的边车模型调用，同样需要计量 token（否则 agent.tokens / 每日配额漏算）。
        // 流式响应里 usage 通常只出现在最后一个 chunk，用原子引用兜住，最后无论成功/失败都入账。
        java.util.concurrent.atomic.AtomicReference<ChatUsage> usage =
                new java.util.concurrent.atomic.AtomicReference<>();
        return model.stream(prompt, List.of(), options)
                .doOnNext(resp -> {
                    if (resp.getUsage() != null) {
                        usage.set(resp.getUsage());
                    }
                })
                .map(ContextWindowAssembler::textOf)
                .reduce(String::concat)
                // 模型可能一个内容块都没吐（被网关截断/纯思考）：必须给空串兜底，
                // 否则 Mono 变空，整个 assemble 链会静默不出消息，这一轮对话直接卡死。
                .defaultIfEmpty("")
                .map(text -> truncate(text.trim(), props.getSummaryMaxChars()))
                .timeout(props.getSummaryTimeout())
                .doFinally(signal -> {
                    // 取消（客户端断开）时不入账；其余终止路径（正常完成 / 错误降级）都记一次
                    if (signal != SignalType.CANCEL) {
                        telemetry.recordModelCall(usage.get(), userId);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("历史摘要失败，本轮降级为「仅最近窗口」：conversation={} delta={} cause={}",
                            conversationId, delta.size(), e.toString());
                    return Mono.just("");
                });
    }

    /** 把流式/非流式的 ChatResponse 统一取成文本。 */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
