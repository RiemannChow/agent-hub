package com.riemann.agent.infrastructure.observability;

import io.agentscope.core.middleware.MiddlewareBase;

import java.util.List;

/**
 * 有序的 AgentScope 中间件链。
 *
 * <p>AgentScope 的 {@code MiddlewareChain} 按<b>装配顺序</b>决定嵌套层级：
 * 索引 0 在最外层，索引越大越靠内。因此这里的顺序不能随意调换——</p>
 * <ol>
 *   <li>{@code OtelTracingMiddleware}（外层）：创建 span 并把 OTel Context 写进 Reactor Context；</li>
 *   <li>{@code LangfuseEnrichmentMiddleware}（内层）：此时 span 已存在，负责补 Langfuse 语义属性。</li>
 * </ol>
 *
 * <p>把顺序固化在一个 bean 里，而不是依赖 Spring 对 {@code @Order} 的排序行为，
 * 避免以后有人往容器里再注册一个 {@code MiddlewareBase} 时把嵌套关系打乱。</p>
 */
public record AgentScopeMiddlewareChain(List<MiddlewareBase> middlewares) {

    public AgentScopeMiddlewareChain {
        middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
    }
}
