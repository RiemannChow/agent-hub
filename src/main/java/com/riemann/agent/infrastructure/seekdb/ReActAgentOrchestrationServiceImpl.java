package com.riemann.agent.infrastructure.seekdb;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.application.MemoryService;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.Message;
import com.riemann.agent.infrastructure.toolkit.tools.SaveMemoryTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AgentScope ReActAgent 与领域编排服务的适配实现。
 *
 * AgentScope Java 2.0 的 ReActAgent 通过 {@code streamEvents(String)} 以响应式流
 * 吐出 31 种强类型事件（io.agentscope.core.event.AgentEvent），本实现把它映射为
 * 领域层事件流，并补发一条领域层的 Completed 事件作为会话结束标记。
 */
@Service
@Profile("seekdb")
public class ReActAgentOrchestrationServiceImpl implements AgentOrchestrationService {

    private final ReActAgent reactAgent;
    private final AgentEventMapper mapper;
    private final MemoryService memoryService;
    /**
     * 当前会话用户载体。编排层在每轮对话前后调用其 setCurrentUser / clearCurrentUser，
     * 使 save_memory 工具能按正确的 userId 落库（AgentScope 工具方法签名无法携带请求上下文）。
     */
    private final SaveMemoryTool saveMemoryTool;

    public ReActAgentOrchestrationServiceImpl(ReActAgent reactAgent, AgentEventMapper mapper,
                                              MemoryService memoryService, SaveMemoryTool saveMemoryTool) {
        this.reactAgent = reactAgent;
        this.mapper = mapper;
        this.memoryService = memoryService;
        this.saveMemoryTool = saveMemoryTool;
    }

    @Override
    public Flux<AgentEvent> converse(Conversation conversation, String userInput) {
        // 召回该用户的长期记忆（最近 N 条），作为本轮上下文背景注入。
        return memoryService.recall(conversation.userId(), userInput)
                .flatMapMany(memoryCtx -> {
                    // 用会话历史 + 召回的长期记忆重建 AgentScope 的 Msg 列表：
                    // buildAgentStream 会自动把 sysPrompt 包成 SystemMessage 前置，因此这里只需按顺序塞入历史消息。
                    List<Msg> history = rebuildHistory(conversation, memoryCtx);
                    // 本轮将要驱动 Agent 流式执行，先把当前用户告知工具集，供 save_memory 按用户落库；
                    saveMemoryTool.setCurrentUser(conversation.userId());
                    // 关键修复：AgentScope 的 ReActAgent 会按 sessionId 在内部 AgentState 里累积消息历史。
                    // 若每次都用默认 sessionId，多轮调用会把历史越攒越多，模型锚定在最早的一条消息上
                    // （如首轮“hi”），导致“同一连接后续回答都等于第一个回答”。这里每轮用唯一 sessionId，
                    // 让 Agent 在单轮内（think→tool→think→answer）仍保留状态，但轮与轮之间互不污染——
                    // 跨轮上下文改由领域层 rebuildHistory（当前消息 + 召回的长期记忆）注入。
                    io.agentscope.core.agent.RuntimeContext rc = io.agentscope.core.agent.RuntimeContext.builder()
                            .sessionId(conversation.id().value() + "-" + UUID.randomUUID())
                            .userId(conversation.userId())
                            .build();
                    // 可观测性：把「稳定的会话 id」挂到 RuntimeContext 上。
                    // 上面那个 sessionId 是每轮唯一的（用于隔离 Agent 内部状态），拿它当 Langfuse
                    // session 会导致每轮一条独立会话；真正的会话维度要用 conversation.id。
                    rc.put(com.riemann.agent.infrastructure.observability.LangfuseSpanAttributes.RUNTIME_SESSION_ID,
                            conversation.id().value());
                    // AgentScope Java 2.0：streamEvents(List<Msg>, RuntimeContext) 返回 Flux<AgentEvent>（框架事件）
                    Flux<io.agentscope.core.event.AgentEvent> upstream = reactAgent.streamEvents(history, rc)
                            // 流结束后清空当前用户，避免跨会话误写
                            .doFinally(signal -> saveMemoryTool.clearCurrentUser());
                    // 注意：必须用 mapNotNull 而非 map().filter()。Reactor 的 map 不允许 mapper 返回 null，
                    // 而 AgentEventMapper 对未映射的框架事件返回 null，会抛
                    // “The mapper returned a null value” -> NullPointerException / UnsupportedOperationException。
                    return upstream
                            .mapNotNull(mapper::toDomain)
                            .concatWithValues(new AgentEvent.Completed(conversation.id().value()));
                });
    }

    /**
     * 将领域消息列表转换为 AgentScope 的 Msg 列表，作为多轮对话上下文。
     *
     * <h3>设计取舍：用“近期窗口 + 反锚定”替代“零历史”</h3>
     * <p>早期版本每轮<b>只发最后一条用户消息</b>，彻底不回放历史，以此规避 Hunyuan 对“最早一条消息”的锚定
     * （首轮问“科普书”，后续“评论数>=10000”仍被当成科普书的追问、复用旧答案）。但零历史让追问（“那本非虚构的呢？”
     * “换成饼图”）丢失上下文、无法解析指代。</p>
     * <p>本实现取<b>当前用户消息之前最近 {@value #MAX_HISTORY} 条</b>作为“短期记忆窗口”注入模型，再明确以
     * 最后一条用户消息作为“本轮任务”。窗口有限且不含会话首条“hi”之类噪声，因此既恢复了多轮指代能力，
     * 又不会让模型锚定到过远的旧主题。反锚定指令保留：明确告知模型“历史窗口可能已过期，必须以最后一条消息为准重新检索”。</p>
     */
    private static final int MAX_HISTORY = 8;

    private List<Msg> rebuildHistory(Conversation conversation, String memoryCtx) {
        List<Message> msgs = conversation.messages();

        // 1) 定位最后一条用户消息（= 本轮任务）
        int lastUserIdx = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof Message.UserMessage) {
                lastUserIdx = i;
                break;
            }
        }
        String current = lastUserIdx >= 0 ? ((Message.UserMessage) msgs.get(lastUserIdx)).content() : "";

        // 2) 拼接反锚定指令 + 长期记忆块（固定前置）
        StringBuilder prefix = new StringBuilder();
        prefix.append("【上下文约束】下面先是“历史窗口”（仅作背景参考，其中的结论/过滤条件可能已过期），"
                + "最后一条“【本轮用户消息】”才是你本轮真正要处理的请求。\n"
                + "你必须依据【本轮用户消息】重新调用工具检索/问数，禁止把历史窗口里的旧结论、旧过滤条件"
                + "（genre / 评分 / 价格 / 评论数等）直接当作本轮答案；若历史窗口与本轮消息冲突，一律以本轮消息为准。\n");
        if (memoryCtx != null && !memoryCtx.isBlank()) {
            prefix.append(memoryCtx).append("\n");
        }

        // 3) 历史窗口：当前用户消息之前的最近 MAX_HISTORY 条（跳过工具内部消息）
        List<Msg> history = new ArrayList<>();
        int windowStart = Math.max(0, lastUserIdx - MAX_HISTORY);
        for (int i = windowStart; i < lastUserIdx; i++) {
            Message m = msgs.get(i);
            if (m instanceof Message.UserMessage um) {
                history.add(new UserMessage(um.content()));
            } else if (m instanceof Message.AssistantMessage am) {
                history.add(new AssistantMessage(am.content()));
            }
            // ToolCall / ToolResult 为工具内部细节，不入模型上下文
        }

        // 4) 最后一条用户消息（带前缀）作为本轮任务，置于末尾
        history.add(new UserMessage(prefix + "\n【本轮用户消息】\n" + current));
        return history;
    }
}
