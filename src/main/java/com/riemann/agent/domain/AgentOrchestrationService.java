package com.riemann.agent.domain;

import reactor.core.publisher.Flux;

import java.util.Map;

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

    /**
     * 带附加属性的对话：用于把「非对话内容」的请求级信息透传进 {@code RuntimeContext}。
     *
     * <p>当前唯一的使用者是 A2A 协作：入站请求带着 {@code metadata.delegationDepth}，
     * 需要一路传到出站委托工具，否则「最大委托跳数」永远读到 0、无法阻止 A→B→A 的循环委托。</p>
     *
     * <p>默认实现忽略属性、退化为两参版本，因此已有实现与调用方无需改动即可继续工作。</p>
     *
     * @param attributes 请求级属性（约定键：{@code delegationDepth}）
     */
    default Flux<AgentEvent> converse(Conversation conversation, String userInput,
                                      Map<String, Object> attributes) {
        return converse(conversation, userInput);
    }

    /**
     * 请求停止某会话当前正在执行的那一轮。
     *
     * <p>语义是「不再继续下一轮推理」，而不是「立刻掐断正在返回的这次模型调用」：
     * 已经发出的模型/工具调用会自然返回，之后循环不再继续。</p>
     *
     * @param conversationId 会话标识
     * @return true 表示确实中断了一个在跑的轮次；false 表示当前没有可中断的轮次
     */
    boolean cancel(String conversationId);
}
