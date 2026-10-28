package com.riemann.agent.infrastructure.config;

import io.agentscope.core.model.Model;

/**
 * 可选的「兜底模型」持有者。
 *
 * <p>单独包一层是因为容器里已经有一个 {@code Model} 类型的 Bean（{@code agentModel}），
 * 再声明一个 {@code Model} Bean 会让按类型注入产生歧义。用独立的包装类型，
 * 注入点只需判断 {@link #isPresent()} 即可，无需 {@code @ConditionalOnProperty} 之类的条件装配。</p>
 *
 * <p>{@code model} 为 {@code null} 表示未配置兜底模型（默认）。</p>
 */
public record FallbackModelHolder(Model model) {

    public boolean isPresent() {
        return model != null;
    }
}
