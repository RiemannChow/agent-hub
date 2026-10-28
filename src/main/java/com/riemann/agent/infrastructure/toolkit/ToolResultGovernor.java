package com.riemann.agent.infrastructure.toolkit;

import com.riemann.agent.infrastructure.metrics.AgentMetrics;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 工具结果治理：<b>单次截断 + 单轮预算 + 连续失败熔断</b>。
 *
 * <h3>为什么挂在 {@code PostActingEvent} 上</h3>
 * <p>{@code PostActingEvent} 提供 {@code getToolResult()} / {@code setToolResult(...)}，
 * 是框架<b>官方提供</b>的「结果已被工具产出、尚未写回模型上下文」这个窗口。
 * 在这里改写，模型下一轮看到的就是治理后的文本；而 {@code ToolResultEndEvent} 只有
 * {@code toolCallId / state}、没有文本，改不了。</p>
 *
 * <h3>为什么还要实现 {@link MiddlewareBase}</h3>
 * <p>预算与熔断必须按「轮」隔离，否则 A 用户的结果会挤占 B 用户的额度。但 Hook 接口拿不到
 * {@link RuntimeContext}（唯一的入口 {@code RuntimeContextAware} 是往单例上写可变字段，
 * 并发多轮会互相覆盖——正是 {@code SaveMemoryTool} 早期那个串号 bug 的同一形状）。
 * 因此这里用 {@code onActing} 拿到<b>方法参数上的</b> RuntimeContext，把
 * {@code toolCallId → 本轮 sessionId} 记下来；{@code PostActingEvent} 再按 toolCallId 取回。</p>
 *
 * <p>拿不到轮次标识时（例如中间件没装配上）自动退化为<b>只做无状态截断</b>，
 * 不启用预算与熔断——宁可少管，也不能让一个全局计数器把所有用户的额度串在一起。</p>
 *
 * <p>熔断是「改写结果 + 明确禁止重试」，不是强行拦截调用。原因很实际：
 * {@code PreActingEvent} 只能改参数、不能取消调用，硬拦需要替换工具名之类的脏手段；
 * 而模型对「工具返回了这句话」的服从度足够高，实测比抛异常更有效。</p>
 */
@Component
public class ToolResultGovernor implements Hook, MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolResultGovernor.class);

    /** 最多同时跟踪多少轮（超出按插入顺序淘汰最旧的），防止异常场景下 map 无限增长。 */
    private static final int MAX_TRACKED_TURNS = 512;

    private final ToolGovernanceProperties props;
    private final AgentMetrics metrics;

    /** toolCallId → 本轮标识。toolCallId 全局唯一，因此这里不存在跨轮串号。 */
    private final Map<String, String> callTurn = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 4096;
        }
    };

    /** 本轮标识 → 累计状态。 */
    private final Map<String, TurnState> turns = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TurnState> eldest) {
            return size() > MAX_TRACKED_TURNS;
        }
    };

    public ToolResultGovernor(ToolGovernanceProperties props, AgentMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    /** 一轮内某个工具的状态。 */
    private static final class TurnState {
        final Map<String, Integer> failures = new HashMap<>();
        final Set<String> open = new HashSet<>();
        int usedChars;
        int truncated;
    }

    // ===================== 中间件：记录 toolCallId → 轮次 =====================

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext runtimeContext, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (props.isEnabled() && runtimeContext != null && input != null && input.toolCalls() != null) {
            String turnKey = turnKeyOf(runtimeContext);
            if (turnKey != null) {
                synchronized (callTurn) {
                    for (ToolUseBlock use : input.toolCalls()) {
                        if (use != null && use.getId() != null) {
                            callTurn.put(use.getId(), turnKey);
                        }
                    }
                }
            }
        }
        return next.apply(input);
    }

    // ===================== Hook：改写工具结果 =====================

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!props.isEnabled() || !(event instanceof PostActingEvent post)) {
            return Mono.just(event);
        }
        try {
            govern(post);
        } catch (Exception e) {
            // 治理是旁路能力：它出错绝不能把这一轮对话搞挂
            log.debug("[ToolGovernor] 治理工具结果时出错，已跳过：{}", e.toString());
        }
        return Mono.just(event);
    }

    private void govern(PostActingEvent post) {
        ToolUseBlock use = post.getToolUse();
        ToolResultBlock result = post.getToolResult();
        if (use == null || result == null) {
            return;
        }
        String turnKey = takeTurnKey(use.getId());
        ToolResultBlock governed = govern(use.getName(), result, turnKey);
        if (governed != result) {
            post.setToolResult(governed);
        }
    }

    /**
     * 治理的核心（纯函数，便于单测）：返回「应该回填给模型的结果」。
     *
     * @param toolName 工具名
     * @param result   工具原始产出
     * @param turnKey  本轮标识；{@code null} 表示拿不到轮次，此时只做无状态截断
     * @return 原对象表示不改写，新对象表示改写
     */
    public ToolResultBlock govern(String toolName, ToolResultBlock result, String turnKey) {
        if (result == null) {
            return result;
        }
        String tool = toolName == null ? "unknown" : toolName;
        TurnState st = turnKey == null ? null : stateOf(turnKey);

        boolean success = result.getState() == ToolResultState.SUCCESS;

        if (st != null) {
            synchronized (st) {
                if (success) {
                    st.failures.remove(tool);
                    st.open.remove(tool);
                } else {
                    int fails = st.failures.merge(tool, 1, Integer::sum);
                    if (fails >= props.getMaxConsecutiveFailures()) {
                        st.open.add(tool);
                    }
                }
                if (st.open.contains(tool)) {
                    int fails = st.failures.getOrDefault(tool, 0);
                    metrics.recordToolCircuitOpen(tool);
                    return replace(result, ToolResultState.ERROR, circuitMessage(tool, fails));
                }
            }
        }

        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty() || !isTextOnly(output)) {
            // 含图片/数据块的结果不做改写：截断会破坏结构化内容
            return result;
        }

        String text = textOf(output);
        int len = text.length();
        ToolResultBlock out = result;

        if (len > props.getMaxResultChars()) {
            String shortened = text.substring(0, props.getMaxResultChars())
                    + "\n…（结果过长已截断，原长 " + len + " 字符，上限 " + props.getMaxResultChars()
                    + "。需要更多内容请缩小查询范围）";
            out = replace(result, result.getState(), shortened);
            metrics.recordToolTruncated(tool);
            len = shortened.length();
        }

        if (st != null) {
            synchronized (st) {
                st.usedChars += len;
                if (st.usedChars > props.getTurnBudgetChars()) {
                    st.truncated++;
                    metrics.recordToolTruncated(tool);
                    return replace(result, result.getState(), budgetMessage());
                }
            }
        }
        return out;
    }

    // ===================== 内部 =====================

    private String turnKeyOf(RuntimeContext rc) {
        String sessionId = rc.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return rc.getUserId() == null ? null : "user:" + rc.getUserId();
    }

    private String takeTurnKey(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        synchronized (callTurn) {
            return callTurn.remove(toolCallId);
        }
    }

    private TurnState stateOf(String turnKey) {
        synchronized (turns) {
            return turns.computeIfAbsent(turnKey, k -> new TurnState());
        }
    }

    private String circuitMessage(String tool, int fails) {
        return "【工具治理】" + tool + " 本轮已连续失败 " + fails + " 次，已熔断。"
                + "禁止再次调用该工具（同样的参数只会得到同样的错误）。"
                + "请改用其它工具，或直接向用户说明当前无法获取该信息。";
    }

    private String budgetMessage() {
        return "【工具治理】本轮工具输出累计已达预算上限（" + props.getTurnBudgetChars()
                + " 字符），本次结果不再回填。请基于已有信息作答，不要再调用工具。";
    }

    private static boolean isTextOnly(List<ContentBlock> output) {
        for (ContentBlock block : output) {
            if (!(block instanceof TextBlock)) {
                return false;
            }
        }
        return true;
    }

    private static String textOf(List<ContentBlock> output) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock t && t.getText() != null) {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }

    /** 保留 id / name / metadata，只换文本与状态。 */
    private static ToolResultBlock replace(ToolResultBlock origin, ToolResultState state, String text) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.builder().text(text).build());
        Map<String, Object> metadata = origin.getMetadata() == null ? Map.of() : origin.getMetadata();
        return new ToolResultBlock(origin.getId(), origin.getName(), blocks, metadata, state);
    }

    /** 当前跟踪的轮次数（排查「预算为什么没生效」时先看这个）。 */
    public int trackedTurns() {
        synchronized (turns) {
            return turns.size();
        }
    }

    /** 清理某轮状态（测试与运维手动复位用）。 */
    public void forget(String turnKey) {
        synchronized (turns) {
            turns.remove(turnKey);
        }
    }

    /** 手动绑定 toolCallId → 轮次（测试用；正常路径由 {@link #onActing} 自动绑定）。 */
    public void bind(String toolCallId, String turnKey) {
        synchronized (callTurn) {
            callTurn.put(toolCallId, turnKey);
        }
    }
}
