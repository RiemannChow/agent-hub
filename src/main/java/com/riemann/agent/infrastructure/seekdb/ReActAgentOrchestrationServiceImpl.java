package com.riemann.agent.infrastructure.seekdb;

import com.riemann.agent.application.AgentEventMapper;
import com.riemann.agent.application.MemoryService;
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
import io.agentscope.core.message.Msg;
import io.micrometer.core.instrument.Timer;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Instant;
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
    private final AgentExecutionProperties executionProperties;
    private final ActiveTurnRegistry turnRegistry;
    private final TurnCancellationService cancellationService;
    private final TurnTelemetry telemetry;
    private final AgentMetrics metrics;
    private final ContextWindowAssembler contextAssembler;

    public ReActAgentOrchestrationServiceImpl(ReActAgent reactAgent, AgentEventMapper mapper,
                                              MemoryService memoryService,
                                              AgentExecutionProperties executionProperties,
                                              ActiveTurnRegistry turnRegistry,
                                              TurnCancellationService cancellationService,
                                              TurnTelemetry telemetry,
                                              AgentMetrics metrics,
                                              ContextWindowAssembler contextAssembler) {
        this.reactAgent = reactAgent;
        this.mapper = mapper;
        this.memoryService = memoryService;
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
        // 用 defer 包一层：登记「本轮在跑」必须发生在真正订阅时，而不是组装 Flux 时，
        // 否则一个从未被订阅的流也会留下一条永远不注销的登记。
        return Flux.defer(() -> {
            String conversationId = conversation.id().value();
            String userId = conversation.userId();
            // 关键修复：AgentScope 的 ReActAgent 会按 sessionId 在内部 AgentState 里累积消息历史。
            // 若每次都用默认 sessionId，多轮调用会把历史越攒越多，模型锚定在最早的一条消息上
            // （如首轮“hi”），导致“同一连接后续回答都等于第一个回答”。这里每轮用唯一 sessionId，
            // 让 Agent 在单轮内（think→tool→think→answer）仍保留状态，但轮与轮之间互不污染——
            // 跨轮上下文改由 ContextWindowAssembler 注入（窗口 + 摘要 + 长期记忆）。
            // 同时，这个 sessionId 也是停止接口定位本轮的唯一凭据（见 ActiveTurnRegistry）。
            String sessionId = conversationId + "-" + UUID.randomUUID();
            ActiveTurnRegistry.ActiveTurn turn =
                    new ActiveTurnRegistry.ActiveTurn(conversationId, userId, sessionId, Instant.now());
            turnRegistry.register(turn);
            Timer.Sample turnSample = metrics.startTurn();

            // 召回该用户的长期记忆（最近 N 条），作为本轮上下文背景注入。
            return memoryService.recall(userId, userInput)
                    .defaultIfEmpty("")
                    // 上下文组装：预算窗口（条数 + 字符）+ 被挤出部分的历史摘要。
                    // 见 ContextWindowAssembler —— 这里替代了原先「硬截断最近 8 条」的做法。
                    .flatMapMany(memoryCtx -> contextAssembler.assemble(conversation, memoryCtx, true)
                            .flatMapMany(window ->
                                    runTurn(conversationId, userId, sessionId, window.messages())))
                    .doFinally(signal -> {
                        turnRegistry.unregister(turn);
                        metrics.stopTurn(turnSample, AgentMetrics.outcomeOf(signal));
                        if (signal == SignalType.CANCEL) {
                            // 下游被取消（客户端断开 SSE / 网关超时）时显式中断本轮：
                            // 否则 Agent 的 ReAct 循环会继续跑完剩余迭代，token 照烧而无人接收。
                            reactAgent.interrupt(userId, sessionId);
                        }
                    });
        });
    }

    /** 真正驱动 Agent 跑一轮：挂载运行时上下文 → 流式事件 → 整轮超时兜底。 */
    private Flux<AgentEvent> runTurn(String conversationId, String userId, String sessionId, List<Msg> history) {
        RuntimeContext rc = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();
        // 可观测性：把「稳定的会话 id」挂到 RuntimeContext 上。
        // 上面那个 sessionId 是每轮唯一的（用于隔离 Agent 内部状态），拿它当 Langfuse
        // session 会导致每轮一条独立会话；真正的会话维度要用 conversation.id。
        rc.put(LangfuseSpanAttributes.RUNTIME_SESSION_ID, conversationId);
        // 并发安全：把本轮调用者上下文按请求挂到 RuntimeContext 上。
        // save_memory 等工具通过方法参数（不带 @ToolParam 的 AgentCallContext）按类型取用，
        // 不再依赖单例 Bean 上的可变字段，因此同用户多标签页并发不会写错 userId。
        rc.put(AgentCallContext.class, new AgentCallContext(userId, conversationId));

        // AgentScope Java 2.0：streamEvents(List<Msg>, RuntimeContext) 返回 Flux<AgentEvent>（框架事件）
        // 旁路计量：模型 token 用量 / 工具调用结果 / 每日配额累加，都不改变事件本身
        Flux<io.agentscope.core.event.AgentEvent> upstream =
                telemetry.instrument(reactAgent.streamEvents(history, rc), userId);
        // 注意：必须用 mapNotNull 而非 map().filter()。Reactor 的 map 不允许 mapper 返回 null，
        // 而 AgentEventMapper 对未映射的框架事件返回 null，会抛
        // “The mapper returned a null value” -> NullPointerException / UnsupportedOperationException。
        Flux<AgentEvent> domain = upstream.mapNotNull(mapper::toDomain);
        // 整轮总时长上限：到点截断并补说明，保证前端一定收到 completed，不会无限转圈
        return TurnTimeout.apply(domain, executionProperties.getTurnTimeout())
                .concatWithValues(new AgentEvent.Completed(conversationId));
    }

    /**
     * 历史上下文的组装已抽到 {@link ContextWindowAssembler}：
     * 「按条数 + 字符预算的最近窗口」+「被挤出部分的历史摘要」+「反锚定说明」+「召回的长期记忆」。
     *
     * <p>原先这里是 {@code MAX_HISTORY = 8} 的硬截断：窗口大小与 token 不成比例，
     * 且被切掉的历史直接消失（用户早前说过的口径到第 9 轮就丢了）。
     * 现在溢出部分会被滚动摘要进上下文，长会话不再只能丢历史。</p>
     */
}
