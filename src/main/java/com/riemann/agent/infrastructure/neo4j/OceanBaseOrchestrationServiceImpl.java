package com.riemann.agent.infrastructure.neo4j;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.Message;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
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
 * OceanBase 知识图谱问答的编排实现（@Profile("oceanbase")）。
 *
 * <p>与 seekdb 版的核心差异：本实现<b>不注入长期记忆召回</b>（MemoryService 属于 seekdb profile，
 * 跨 profile 引入会造成 bean 缺失），仅用当前会话历史重建上下文驱动 ReActAgent。
 * ReActAgent 会自行调用 {@link OceanBaseToolkit} 里的 ob_* 工具检索 Neo4j 图谱后作答，
 * 因此“管理 OceanBase 任何问题”的能力由「图谱检索工具 + LLM 推理」组合实现。</p>
 *
 * <p>因为 {@code AgentChatController} 依赖的是 {@code AgentOrchestrationService} 接口，
 * 切到 oceanbase profile 即自动启用本实现，Controller 零改动。</p>
 */
@Service
@Profile("oceanbase")
public class OceanBaseOrchestrationServiceImpl implements AgentOrchestrationService {

    /** 注入模型的短期历史窗口大小（不含工具内部消息）。 */
    private static final int MAX_HISTORY = 8;

    private final ReActAgent reactAgent;
    private final AgentEventMapper mapper;

    public OceanBaseOrchestrationServiceImpl(ReActAgent reactAgent, AgentEventMapper mapper) {
        this.reactAgent = reactAgent;
        this.mapper = mapper;
    }

    @Override
    public Flux<AgentEvent> converse(Conversation conversation, String userInput) {
        List<Msg> history = buildHistory(conversation);
        RuntimeContext rc = RuntimeContext.builder()
                .sessionId(conversation.id().value() + "-" + UUID.randomUUID())
                .userId(conversation.userId())
                .build();
        return reactAgent.streamEvents(history, rc)
                .mapNotNull(mapper::toDomain)
                .concatWithValues(new AgentEvent.Completed(conversation.id().value()));
    }

    /**
     * 取当前用户消息之前最近 {@value #MAX_HISTORY} 条 用户/助手 消息作为上下文窗口，
     * 跳过工具调用/结果等内部消息（AgentScope 已在其事件流中呈现，无需再喂给模型）。
     */
    private List<Msg> buildHistory(Conversation conversation) {
        List<Msg> history = new ArrayList<>();
        List<Message> msgs = conversation.messages();
        int count = 0;
        for (int i = msgs.size() - 1; i >= 0 && count < MAX_HISTORY; i--) {
            Message m = msgs.get(i);
            if (m instanceof Message.UserMessage um) {
                history.add(0, new UserMessage(um.content()));
                count++;
            } else if (m instanceof Message.AssistantMessage am) {
                history.add(0, new AssistantMessage(am.content()));
                count++;
            }
        }
        return history;
    }
}
