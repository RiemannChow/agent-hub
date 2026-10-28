package com.riemann.agent.infrastructure.neo4j;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.infrastructure.context.ContextWindowAssembler;
import com.riemann.agent.infrastructure.execution.ActiveTurnRegistry;
import com.riemann.agent.infrastructure.execution.AgentExecutionProperties;
import com.riemann.agent.infrastructure.execution.TurnCancellationService;
import com.riemann.agent.infrastructure.execution.TurnTimeout;
import com.riemann.agent.infrastructure.metrics.AgentMetrics;
import com.riemann.agent.infrastructure.metrics.TurnTelemetry;
import com.riemann.agent.infrastructure.observability.LangfuseSpanAttributes;
import com.riemann.agent.infrastructure.toolkit.tools.AgentCallContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Map;
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

    private final ReActAgent reactAgent;
    private final AgentEventMapper mapper;
    private final AgentExecutionProperties executionProperties;
    private final ActiveTurnRegistry turnRegistry;
    private final TurnCancellationService cancellationService;
    private final TurnTelemetry telemetry;
    private final AgentMetrics metrics;
    private final ContextWindowAssembler contextAssembler;

    public OceanBaseOrchestrationServiceImpl(ReActAgent reactAgent, AgentEventMapper mapper,
                                             AgentExecutionProperties executionProperties,
                                             ActiveTurnRegistry turnRegistry,
                                             TurnCancellationService cancellationService,
                                             TurnTelemetry telemetry,
                                             AgentMetrics metrics,
                                             ContextWindowAssembler contextAssembler) {
        this.reactAgent = reactAgent;
        this.mapper = mapper;
        this.executionProperties = executionProperties;
        this.turnRegistry = turnRegistry;
        this.cancellationService = cancellationService;
        this.telemetry = telemetry;
        this.metrics = metrics;
        this.contextAssembler = contextAssembler;
    }

    @Override
    public boolean cancel(String conversationId) {
        return cancellationService.cancel(conversationId);
    }

    @Override
    public Flux<AgentEvent> converse(Conversation conversation, String userInput) {
        return converse(conversation, userInput, Map.of());
    }

    @Override
    public Flux<AgentEvent> converse(Conversation conversation, String userInput,
                                     Map<String, Object> attributes) {
        // defer：登记「本轮在跑」必须发生在真正订阅时，否则未被订阅的流会留下永不注销的登记
        return Flux.defer(() -> {
            String conversationId = conversation.id().value();
            String userId = conversation.userId();
            // sessionId 每轮唯一：既隔离 Agent 内部状态，也是停止接口定位本轮的凭据
            String sessionId = conversationId + "-" + UUID.randomUUID();
            ActiveTurnRegistry.ActiveTurn turn =
                    new ActiveTurnRegistry.ActiveTurn(conversationId, userId, sessionId, Instant.now());
            turnRegistry.register(turn);
            Timer.Sample turnSample = metrics.startTurn();

            // 上下文组装：预算窗口 + 溢出部分摘要（与 seekdb 版共用同一实现）。
            // 本 profile 不注入长期记忆，也不注入反锚定说明——纯知识问答不涉及“旧过滤条件被复用”的问题。
            return contextAssembler.assemble(conversation, "", false)
                    .flatMapMany(window -> {
                        RuntimeContext rc = RuntimeContext.builder()
                                .sessionId(sessionId)
                                .userId(userId)
                                .build();
                        // 可观测性：sessionId 是每轮唯一的，Langfuse 的会话维度要用稳定的 conversation.id
                        rc.put(LangfuseSpanAttributes.RUNTIME_SESSION_ID, conversationId);
                        // 调用者上下文（含委托跳数）：出站委托工具据此判断能否继续往外委托
                        rc.put(AgentCallContext.class, new AgentCallContext(
                                userId, conversationId, AgentCallContext.depthOf(attributes)));
                        // 整轮总时长上限：到点截断并补说明，保证前端一定收到 completed，不会无限转圈
                        return TurnTimeout.apply(
                                        telemetry.instrument(reactAgent.streamEvents(window.messages(), rc), userId)
                                                .mapNotNull(mapper::toDomain),
                                        executionProperties.getTurnTimeout())
                                .concatWithValues(new AgentEvent.Completed(conversationId));
                    })
                    .doFinally(signal -> {
                        turnRegistry.unregister(turn);
                        metrics.stopTurn(turnSample, AgentMetrics.outcomeOf(signal));
                        if (signal == SignalType.CANCEL) {
                            // 下游取消（客户端断开）时显式中断，避免循环继续烧 token
                            reactAgent.interrupt(userId, sessionId);
                        }
                    });
        });
    }

    /**
     * 历史上下文的组装已抽到 {@link ContextWindowAssembler}（预算窗口 + 滚动摘要），
     * 原先这里同样是 {@code MAX_HISTORY = 8} 的硬截断。
     */
}
