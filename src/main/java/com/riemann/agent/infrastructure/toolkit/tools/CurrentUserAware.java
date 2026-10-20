package com.riemann.agent.infrastructure.toolkit.tools;

/**
 * 需要感知“当前会话用户”的工具实现此接口。
 *
 * <p>AgentScope 的工具方法签名无法携带请求上下文（如当前 userId），
 * 故由编排层在每轮对话前后调用 {@link #setCurrentUser(String)} / {@link #clearCurrentUser()}，
 * 使工具（如 save_memory）能按正确的 userId 落库，避免跨会话错乱。</p>
 */
public interface CurrentUserAware {

    /** 每轮对话开始时由编排层设置当前用户。 */
    void setCurrentUser(String userId);

    /** 对话流结束后由编排层清空，避免跨会话误写。 */
    void clearCurrentUser();
}
