package com.riemann.agent.infrastructure.toolkit.tools;

/**
 * 单轮对话的调用者上下文，供工具方法「按请求」读取当前用户。
 *
 * <p><b>为什么需要它：</b>AgentScope 的工具方法签名本身不带请求上下文，
 * 早期实现用 {@code volatile} 实例字段承接 userId，但 Spring Bean 是单例、
 * Agent 也是单例，该字段天然是共享可变状态——同一用户开两个标签页并发对话时，
 * A 会话的 {@code save_memory} 可能把内容写进 B 会话的用户名下。</p>
 *
 * <p><b>本方案：</b>AgentScope 支持在工具方法上声明「不带 {@code @ToolParam} 的参数」，
 * 这类参数不会进入给 LLM 的 tool schema，而是在调用时由
 * {@code RuntimeContext.get(参数类型)} 解析注入（见 {@code ToolMethodInvoker#resolveContextParameter}）。
 * 因此编排层只需在每轮 {@code RuntimeContext} 上挂一个本对象，工具方法按参数取值即可，
 * 状态完全随请求走，不存在跨请求共享。</p>
 *
 * @param userId         当前登录用户
 * @param conversationId 当前会话 id（稳定值，非每轮 UUID）
 */
public record AgentCallContext(String userId, String conversationId) {

    public AgentCallContext {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("AgentCallContext.userId 不能为空");
        }
    }
}
