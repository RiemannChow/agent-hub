package com.riemann.agent.application;

import com.riemann.agent.application.quota.AgentQuotaService;
import com.riemann.agent.application.quota.QuotaExceededException;
import com.riemann.agent.domain.*;
import com.riemann.agent.domain.AgentEvent;
import com.riemann.agent.domain.AgentOrchestrationService;
import com.riemann.agent.domain.AgentState;
import com.riemann.agent.domain.Conversation;
import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.domain.ConversationRepository;
import com.riemann.agent.domain.Message;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 应用层用例服务：编排“保存用户消息 → 调用编排 → 持久化状态”。
 * 整条链路运行在响应式非阻塞链路上，禁止出现 .block()。
 */
@Service
public class ConversationAppService {

    private final ConversationRepository repository;
    private final AgentOrchestrationService orchestration;
    private final MemoryExtractor memoryExtractor;
    private final MemoryService memoryService;
    private final AgentQuotaService quota;

    public ConversationAppService(ConversationRepository repository,
                                  AgentOrchestrationService orchestration,
                                  MemoryExtractor memoryExtractor,
                                  MemoryService memoryService,
                                  AgentQuotaService quota) {
        this.repository = repository;
        this.orchestration = orchestration;
        this.memoryExtractor = memoryExtractor;
        this.memoryService = memoryService;
        this.quota = quota;
    }

    /**
     * 处理一次对话。若会话不存在则新建。
     *
     * <p>进入真正的编排之前先过配额：并发名额在 {@code defer} 里占用，
     * 在事件流终止（正常结束 / 异常 / 客户端取消）时通过 {@code doFinally} 归还，
     * 保证三条终止路径都不会把许可漏掉。</p>
     *
     * @throws QuotaExceededException 并发超限或当日 token 预算用尽（由控制器转成 SSE error 帧）
     */
    public Flux<AgentEvent> chat(String userId, String userInput, ConversationId conversationId) {
        return chat(userId, userInput, conversationId, Map.of());
    }

    /**
     * 带请求级属性的对话（A2A 委托等场景使用）。
     *
     * <p>属性会一路透传到 {@code RuntimeContext}，让工具能读到「这次请求是第几跳委托」。</p>
     */
    public Flux<AgentEvent> chat(String userId, String userInput, ConversationId conversationId,
                                 Map<String, Object> attributes) {
        Map<String, Object> attrs = (attributes == null) ? Map.of() : attributes;
        return Flux.defer(() -> {
            quota.acquire(userId);
            return doChat(userId, userInput, conversationId, attrs)
                    .doFinally(signal -> quota.release(userId));
        });
    }

    private Flux<AgentEvent> doChat(String userId, String userInput, ConversationId conversationId,
                                    Map<String, Object> attributes) {
        Mono<Conversation> convMono = repository.findById(conversationId)
                .switchIfEmpty(Mono.fromCallable(() -> createConversation(userId, conversationId)));

        return convMono.flatMapMany(conv -> {
            // 1) 追加并落库本轮用户消息
            Message.UserMessage userMsg = new Message.UserMessage(conv.id().value(), userInput, Instant.now());
            Conversation withUser = conv.addMessage(userMsg);
            Mono<Void> persistUser = repository.save(withUser)
                    .then(repository.appendMessages(withUser.id(), List.of(userMsg)));

            // 2) 驱动编排（注入完整历史），并捕获本轮产生的工具/助手消息用于持久化
            List<Message> captured = new ArrayList<>();
            StringBuilder assistantText = new StringBuilder();
            // 同一回合内消息时间戳严格递增，保证按 created_at 回放顺序稳定
            long[] seq = {System.currentTimeMillis()};

            Flux<AgentEvent> events = orchestration.converse(withUser, userInput, attributes)
                    .doOnNext(ev -> {
                        if (ev instanceof AgentEvent.ToolCalling tc) {
                            captured.add(new Message.ToolCallMessage(
                                    conv.id().value(), tc.toolName(), tc.arguments(),
                                    Instant.ofEpochMilli(seq[0]++)));
                        } else if (ev instanceof AgentEvent.ToolResult tr) {
                            captured.add(new Message.ToolResultMessage(
                                    conv.id().value(), tr.toolName(), tr.result(),
                                    Instant.ofEpochMilli(seq[0]++)));
                        } else if (ev instanceof AgentEvent.Token tk) {
                            assistantText.append(tk.delta());
                        } else if (ev instanceof AgentEvent.Completed) {
                            String text = assistantText.toString();
                            if (!text.isBlank()) {
                                captured.add(new Message.AssistantMessage(
                                        conv.id().value(), text, Instant.ofEpochMilli(seq[0]++)));
                            }
                            // 持久化本轮产生的工具/助手消息（增量，避免重复插入历史）
                            repository.appendMessages(withUser.id(), captured).subscribe();
                            // 更新 Agent 运行态
                            repository.updateAgentState(
                                            conv.id(), conv.agentState().incStep().withStatus("IDLE"))
                                    .subscribe();
                            // 异步抽取本轮稳定事实 → 写入长期记忆（使用正确 userId，非工具里的错乱 id）。
                            // 不阻塞 SSE 响应；结果于下一轮被语义召回注入上下文。
                            // 用 concatMap（串行）而非 flatMap：保证去重/合并逻辑中“先查 nearest 再写”的
                            // 读取-修改-写入不被并发打乱（否则同批次的重复事实会同时看到空表而双双插入）。
                            if (!text.isBlank()) {
                                memoryExtractor.extract(userInput, text)
                                        .flatMapMany(Flux::fromIterable)
                                        .concatMap(fact -> memoryService.save(userId, fact))
                                        .subscribe();
                            }
                        }
                    });

            // 3) 先落库用户消息，再流式吐出编排事件
            return persistUser.thenMany(events);
        });
    }

    private Conversation createConversation(String userId, ConversationId id) {
        return new Conversation(id, userId, null, AgentState.initial("seek-agent"));
    }

    /** 便捷方法：为未指定会话的场景生成新会话标识。 */
    public ConversationId newConversationId() {
        return new ConversationId(UUID.randomUUID().toString());
    }

    /**
     * 请求停止某会话当前正在执行的一轮。
     *
     * <p>只负责转发到编排层（那里才知道 agent 与 (userId, sessionId) 的对应关系）；
     * 真正的中断标志由 AgentScope 在 ReAct 循环的下一轮迭代处检查并生效。</p>
     *
     * @return true 表示确实中断了一个在跑的轮次
     */
    public boolean cancelTurn(String conversationId) {
        return orchestration.cancel(conversationId);
    }

    // ===================== 历史记录查询（侧边栏用） =====================

    /** 列出某用户的所有会话概要（用于左侧历史栏）。 */
    public Flux<ConversationSummary> listConversations(String userId) {
        return repository.findByUserId(userId)
                .map(this::summarize);
    }

    /** 获取某个会话的完整消息（点击历史项后回填聊天区）。 */
    public Mono<ConversationDetail> getConversation(ConversationId id) {
        return repository.findById(id).map(conv -> {
            var messages = conv.messages().stream().map(m -> switch (m) {
                case Message.UserMessage um       -> new MessageDto("user", um.content(), null, null);
                case Message.AssistantMessage am  -> new MessageDto("assistant", am.content(), null, null);
                case Message.ToolCallMessage tm   -> new MessageDto("tool_call", null, tm.toolName(), tm.arguments());
                case Message.ToolResultMessage rm  -> new MessageDto("tool_result", rm.result(), rm.toolName(), null);
            }).toList();
            return new ConversationDetail(conv.id().value(), messages);
        });
    }

    private ConversationSummary summarize(Conversation conv) {
        String title = conv.messages().stream()
                .filter(m -> m instanceof Message.UserMessage)
                .map(m -> ((Message.UserMessage) m).content())
                .findFirst()
                .orElse("(空会话)");
        String preview = title.length() > 28 ? title.substring(0, 28) + "…" : title;
        return new ConversationSummary(conv.id().value(), preview, conv.lastUpdated().toEpochMilli());
    }

    /** 会话概要 DTO（侧边栏）。 */
    public record ConversationSummary(String conversationId, String title, long updatedAt) {}

    /** 会话详情 DTO（含消息列表）。 */
    public record ConversationDetail(String conversationId, java.util.List<MessageDto> messages) {}

    /** 单条消息 DTO。 */
    public record MessageDto(String type, String content, String toolName, String arguments) {}
}
