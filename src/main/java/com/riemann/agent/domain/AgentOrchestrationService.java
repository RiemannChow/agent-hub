package com.riemann.agent.domain;

import reactor.core.publisher.Flux;

/**
 * 领域服务：Agent 编排服务（纯接口，不依赖 Spring）。
 * 负责驱动推理循环并以响应式事件流返回过程。
 */
public interface AgentOrchestrationService {

    /**
     * 与指定会话进行一次对话。
     *
     * <p>传入的 {@code conversation} 已包含本轮用户输入消息，且携带了之前所有回合的
     * 用户/助手/工具消息。实现负责据此重建历史并注入 ReActAgent，使多轮对话具备上下文。
     *
     * @param conversation 会话聚合（含完整历史 + 本轮用户消息）
     * @param userInput    用户输入（与 conversation 中最后一条 UserMessage 对应，保留以便日志/调试）
     * @return 领域事件流（Thinking / ToolCalling / ToolResult / Token / Completed）
     */
    Flux<AgentEvent> converse(Conversation conversation, String userInput);
}
